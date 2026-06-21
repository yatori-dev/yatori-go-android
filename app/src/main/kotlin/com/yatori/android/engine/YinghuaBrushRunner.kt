package com.yatori.android.engine

import com.yatori.android.domain.model.*
import com.yatori.android.engine.ocr.OcrEngine
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 英华/仓辉平台刷课 Runner — 完整对照 Go api/yinghua/YingHuaApi.go 重写
 *
 * 登录：GET /service/code?r= (验证码OCR shape=1,18) → POST /user/login.json(multipart) → token从redirect解析
 * 课程：POST /api/course/list.json(multipart token) → result.list[]
 * 视频：POST /api/course/chapter.json(multipart courseId) → result.list[]
 * 提交：POST /api/node/study.json(multipart nodeId+studyId+studyTime)
 * 心跳：POST /api/online.json(multipart token) — 每5分钟
 */
@Singleton
class YinghuaBrushRunner @Inject constructor(
    private val ocrEngine: OcrEngine
) : PlatformBrushRunner {

    private val client = OkHttpClient.Builder()
        .cookieJar(SimpleCookieJar())
        .followRedirects(false)   // 登录用302 redirect解析token，不自动跳转
        .build()

    override suspend fun run(
        taskId: String, account: Account, settings: AppSettings,
        scope: CoroutineScope, onProgress: (TaskProgress) -> Unit,
 log: suspend (String, String) -> Unit
) = withContext(Dispatchers.IO) {
        log("INFO", "正在识别验证码并登录...")
        val token = login(account)
        log("INFO", "登录成功")

        // 心跳协程（等价 Go keepAliveLogin goroutine）
        val keepAlive = scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                runCatching { keepAlive(account.url, token) }
            }
        }

        try {
            val courses = fetchCourseList(account.url, token)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING, totalCourses = courses.size))

            val mode = account.coursesCustom.videoMode
            val jobs = mutableListOf<Job>()

            courses.forEachIndexed { _, course ->
                val name = course.optString("name")
                val include = account.coursesCustom.includeCourses
                val exclude = account.coursesCustom.excludeCourses
                if (exclude.isNotEmpty() && exclude.contains(name)) return@forEachIndexed
                if (include.isNotEmpty() && !include.contains(name)) return@forEachIndexed

                val job = scope.launch {
                    log("INFO", "开始处理课程: $name")
                    brushCourse(account.url, token, course, mode, log)
                    if (mode == VideoMode.VIOLENT) {
                        brushCourse(account.url, token, course, VideoMode.DERED, log)
                    }
                    log("INFO", "课程完成: $name")
                }
                if (mode == VideoMode.VIOLENT) jobs.add(job) else job.join()
            }
            jobs.forEach { it.join() }
        } finally {
            keepAlive.cancel()
        }
    }

    // ─── 登录（等价 Go YingHuaLoginAction）────────────────────────────────────────

    private suspend fun login(account: Account): String = withContext(Dispatchers.IO) {
        // 等价 Go for{} 无限重试直到验证码识别正确并登录成功
        var attempt = 0
        while (true) {
            attempt++
            currentCoroutineContext().ensureActive()
            val r = Random.nextDouble().toString()
            val codeResp = runCatching {
                client.newCall(Request.Builder().url("${account.url}/service/code?r=$r").build()).execute()
            }.getOrNull() ?: continue
            val imgBytes = codeResp.body?.bytes() ?: continue

            // 合成到白色背景，消除 PNG 透明度预乘导致的颜色偏差
            val raw = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size) ?: continue
            val bmp = android.graphics.Bitmap.createBitmap(raw.width, raw.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(raw, 0f, 0f, null)
                if (raw != it) raw.recycle()
            }

            val code = ocrEngine.recognizeSemi(bmp, 18)
                .filter { it.isLetterOrDigit() && it.code < 128 }  // 只保留英文字母和数字
            bmp.recycle()
            android.util.Log.d("YinghuaOCR", "attempt=$attempt OCR='$code'")
            if (code.length < 4) continue  // 不足4位说明识别失败，重拉

            // 2. POST /user/login.json (multipart)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("username", account.account)
                .addFormDataPart("password", account.password)
                .addFormDataPart("code", code)
                .addFormDataPart("redirect", account.url)
                .build()
            val loginResp = client.newCall(
                Request.Builder().url("${account.url}/user/login.json").post(body).build()
            ).execute()
            val respBody = loginResp.body?.string() ?: "{}"
            android.util.Log.d("YinghuaOCR", "login resp=$respBody")
            val json = JSONObject(respBody)

            val msg = json.optString("msg", null)
            // 有 redirect 说明登录成功，直接提取 token（等价 Go else 分支）
            if (!json.isNull("redirect")) {
                val redirect = json.optString("redirect")
                val token = redirect.substringAfter("token=", "").substringBefore("&")
                if (token.isNotEmpty()) return@withContext token
            }
            // 等价 Go: msg == 验证码有误 / msg 为 null → continue 重试
            if (msg == null || msg == "验证码有误！" || msg.contains("验证码")) continue
            // 其他实质性错误（账号密码错误等）
            throw RuntimeException("英华登录失败: $msg")
        }
        error("unreachable")
    }

    // ─── 课程列表（POST /api/course/list.json）────────────────────────────────────

    private suspend fun fetchCourseList(preUrl: String, token: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val body = multipart(token) { addFormDataPart("type", "0") }
        val resp = client.newCall(
            Request.Builder().url("$preUrl/api/course/list.json").post(body).build()
        ).execute()
        val raw = resp.body?.string() ?: ""
        android.util.Log.d("YinghuaBrush", "fetchCourseList resp=$raw")
        val json = JSONObject(raw)
        if (json.optString("msg") != "获取数据成功") {
            android.util.Log.w("YinghuaBrush", "fetchCourseList failed: ${json.optString("msg")}")
            return@withContext emptyList()
        }
        val list = json.optJSONObject("result")?.optJSONArray("list") ?: return@withContext emptyList()
        (0 until list.length()).map { list.getJSONObject(it) }
    }

    // ─── 单课程刷取（等价 Go VideosListAction + videoAction）────────────────────

    private suspend fun brushCourse(
        preUrl: String, token: String, course: JSONObject, mode: VideoMode,
        log: suspend (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val courseId = course.opt("id")?.toString() ?: return@withContext

        // 接口一：章节结构 /api/course/chapter.json → nodeList（videoDuration 是字符串）
        val chapterBody = multipart(token) { addFormDataPart("courseId", courseId) }
        val chapterResp = client.newCall(
            Request.Builder().url("$preUrl/api/course/chapter.json").post(chapterBody).build()
        ).execute()
        val chapterJson = JSONObject(chapterResp.body?.string() ?: "{}")
        if (chapterJson.optString("msg") != "获取数据成功") return@withContext
        val chapterList = chapterJson.optJSONObject("result")?.optJSONArray("list") ?: return@withContext

        // 平铺所有 nodeList
        val nodeMap = mutableMapOf<String, JSONObject>() // id -> node
        for (i in 0 until chapterList.length()) {
            val chapter = chapterList.getJSONObject(i)
            val nodes = chapter.optJSONArray("nodeList") ?: continue
            for (j in 0 until nodes.length()) {
                val node = nodes.getJSONObject(j)
                val id = node.opt("id")?.toString() ?: continue
                nodeMap[id] = node
            }
        }

        // 接口二：Android 观看记录 /api/record/video.json → progress(0-100)、viewedDuration
        var page = 1
        var pageCount = 999
        while (page <= pageCount) {
            currentCoroutineContext().ensureActive()
            val recBody = multipart(token) {
                addFormDataPart("courseId", courseId)
                addFormDataPart("page", page.toString())
                addFormDataPart("pageSize", "20")
            }
            val recResp = client.newCall(
                Request.Builder().url("$preUrl/api/record/video.json").post(recBody).build()
            ).execute()
            val recJson = JSONObject(recResp.body?.string() ?: "{}")
            pageCount = recJson.optJSONObject("result")?.optJSONObject("pageInfo")
                ?.optInt("pageCount", 1) ?: 1
            val recList = recJson.optJSONObject("result")?.optJSONArray("list") ?: break
            if (recList.length() == 0) break
            for (i in 0 until recList.length()) {
                val rec = recList.getJSONObject(i)
                val id = rec.opt("id")?.toString() ?: continue
                nodeMap[id]?.apply {
                    put("progress", rec.optInt("progress", 0))
                    put("viewedDuration", rec.optInt("viewedDuration", 0))
                }
            }
            page++
        }

        // 接口三：PC 观看记录 /user/study_record/video.json → errorMessage
        var page2 = 1
        var pageCount2 = 999
        val ts = System.currentTimeMillis() / 1000
        while (page2 <= pageCount2) {
            currentCoroutineContext().ensureActive()
            val url2 = "$preUrl/user/study_record/video.json?courseId=$courseId&_=$ts&page=$page2"
            val rec2Resp = client.newCall(
                Request.Builder().url(url2)
                    .header("Authorization", token).get().build()
            ).execute()
            val rec2Json = JSONObject(rec2Resp.body?.string() ?: "{}")
            pageCount2 = rec2Json.optJSONObject("pageInfo")?.optInt("pageCount", 1) ?: 1
            val rec2List = rec2Json.optJSONArray("list") ?: break
            if (rec2List.length() == 0) break
            for (i in 0 until rec2List.length()) {
                val rec = rec2List.getJSONObject(i)
                val id = rec.optString("id", "")
                nodeMap[id]?.put("errorMessage", rec.optString("errorMessage", ""))
            }
            page2++
        }

        android.util.Log.d("YinghuaBrush", "course=$courseId nodes=${nodeMap.size}")
        val courseName = course.optString("name", courseId)

        // 刷取未完成的视频节点
        val nodeJobs = mutableListOf<kotlinx.coroutines.Job>()
        for ((_, node) in nodeMap) {
            if (!node.optBoolean("tabVideo", false)) continue
            when (mode) {
                VideoMode.NORMAL -> brushVideoNode(preUrl, token, node, courseName, log)
                VideoMode.VIOLENT -> {
                    val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        brushVideoNode(preUrl, token, node, courseName, log)
                    }
                    nodeJobs.add(job)
                }
                VideoMode.DERED -> {
                    if (node.optString("errorMessage").contains("并行")) {
                        brushVideoNode(preUrl, token, node, courseName, log)
                    }
                }
                else -> {}
            }
        }
        nodeJobs.forEach { it.join() }
    }

    // ─── 单节点学时提交（等价 Go videoAction — 每5秒步进5秒）────────────────────

    private suspend fun brushVideoNode(
        preUrl: String, token: String, node: JSONObject,
        courseName: String = "", log: suspend (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val nodeId = node.opt("id")?.toString() ?: return@withContext
        // videoDuration 在章节接口里是字符串
        val videoDuration = node.optString("videoDuration", "0").toIntOrNull() ?: 0
        if (videoDuration <= 0) return@withContext
        // progress 经接口二合并后是 0-100 整数
        if (node.optInt("progress", 0) >= 100) return@withContext
        var studyTime = node.optInt("viewedDuration", 0)
        var studyId = "0"
        val nodeName = node.optString("name", nodeId)

        log("INFO", "[$courseName] 开始刷视频: $nodeName ($studyTime/${videoDuration}s)")
        while (studyTime < videoDuration) {
            currentCoroutineContext().ensureActive()
            studyTime = (studyTime + 5).coerceAtMost(videoDuration)
            val body = multipart(token) {
                addFormDataPart("nodeId", nodeId)
                addFormDataPart("terminal", "Android")
                addFormDataPart("studyTime", studyTime.toString())
                addFormDataPart("studyId", studyId)
            }
            val resp = client.newCall(
                Request.Builder().url("$preUrl/api/node/study.json").post(body).build()
            ).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val msg = json.optString("msg")
            if (msg == "提交学时成功!") {
                log("INFO", "[$courseName] $nodeName 进度: $studyTime/$videoDuration s")
            } else if (msg.isNotEmpty() && msg != "提交学时成功!") {
                log("WARN", "[$courseName] $nodeName: $msg")
            }
            if (msg.contains("登录超时") || msg.contains("未登录")) return@withContext
            if (msg == "提交学时成功!" || msg.isEmpty()) {
                studyId = json.optJSONObject("result")?.optJSONObject("data")
                    ?.opt("studyId")?.toString() ?: studyId
            }
            if (studyTime < videoDuration) delay(5_000L)
        }
        log("INFO", "[$courseName] 视频完成: $nodeName")
    }

    // ─── 心跳（POST /api/online.json）────────────────────────────────────────────

    private suspend fun keepAlive(preUrl: String, token: String) = withContext(Dispatchers.IO) {
        val body = multipart(token) {}
        client.newCall(Request.Builder().url("$preUrl/api/online.json").post(body).build()).execute()
    }

    // ─── 工具：构建 multipart body（所有接口都带 platform/version/token）──────────

    private fun multipart(token: String, block: MultipartBody.Builder.() -> Unit): RequestBody {
        return MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("platform", "Android")
            addFormDataPart("version", "1.4.8")
            addFormDataPart("token", token)
            block()
        }.build()
    }
}
