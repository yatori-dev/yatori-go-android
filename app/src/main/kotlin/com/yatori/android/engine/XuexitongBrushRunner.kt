package com.yatori.android.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.yatori.android.domain.model.*
import com.yatori.android.engine.face.FaceBypassEngine
import com.yatori.android.engine.ocr.OcrEngine
import com.yatori.android.engine.slider.SliderEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 学习通刷课Runner — 完整对照 Go api/xuexitong/ + aggregation/xuexitong/ 重写
 *
 * 登录：POST passport2.chaoxing.com/fanyalogin
 * 课程：GET mooc1-api.chaoxing.com/mycourse/backclazzdata
 * 章节：GET mooc1-api.chaoxing.com/gas/knowledge?id={knowledgeId}&courseid={courseId}&fields=...&token=4faa8662c59590c6f43ae9fe5b002b42
 * 卡片：GET mooc1-api.chaoxing.com/knowledge/cards?clazzid=&courseid=&knowledgeid=&num=0&isPhone=1&control=true&cpi=
 * 视频DTO：GET ?k={fid}&flag=normal&_dc={ts}
 * 视频提交：GET mooc1.chaoxing.com/mooc-ans/multimedia/log/a/{cpi}/{dtoken}?...&enc=md5(...)
 *   enc = md5("[classId][userId][jobId][objectId][playingTime*1000][d_yHJ!$pdA~5][duration*1000][0_{duration}]")
 */
@Singleton
class XuexitongBrushRunner @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val ocrEngine: OcrEngine,
    private val sliderEngine: SliderEngine,
    private val faceBypassEngine: FaceBypassEngine
) : PlatformBrushRunner {

    private val client = OkHttpClient.Builder()
        .cookieJar(SimpleCookieJar())
        .followRedirects(true)
        // 等价 Go GetUA("mobile") — 缺少这个 UA 服务器会返回 HTML 登录页
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 8.1.0; MI 5X Build/OPM1.171019.019; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.99 Mobile Safari/537.36 (schild:ce5175d20950c8ee955fb03246f762da) (device:MI 5X) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.7.2_android_phone_10936_311 (@Kalimdor)_76c82452584d47e39ab79aa54ea86554")
                .build())
        }
        .build()

    // 等价 Go xxtAiAnswerApi 中 Timeout: 0 ── SSE流不能有读超时，继承 client 全部设置（UA、Cookie等）
    private val aiClient by lazy { client.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build() }

    // 等价 Go lockByCourse：防止同一课程并发调用XXT AI
    private val aiCourseLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** 安全解析 JSON，服务端返回 HTML 时返回 null（等价 Go 的 err 检查） */
    private fun safeJson(body: String?): JSONObject? {
        if (body == null || body.trimStart().startsWith("<")) return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private var userId = ""

    override suspend fun run(
        taskId: String, account: Account, settings: AppSettings,
        scope: CoroutineScope, onProgress: (TaskProgress) -> Unit,
 log: suspend (String, String) -> Unit
) = withContext(Dispatchers.IO) {
        login(account)
        log("INFO", "登录成功")
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING, currentPhase = "登录成功"))

        val courses = fetchCourses(account)
        log("INFO", "拉取课程成功 (共 ${courses.size} 门)")
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING, totalCourses = courses.size, currentPhase = "扫描到 ${courses.size} 门课程"))

        // 过滤出需要学习的课程
        val filteredCourses = courses.mapIndexedNotNull { idx, course ->
            val name = course.optString("courseName", "")
            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses

            if (exclude.isNotEmpty() && exclude.any { name.contains(it, ignoreCase = true) }) {
                return@mapIndexedNotNull null
            }
            if (include.isNotEmpty() && !include.any { name.contains(it, ignoreCase = true) }) {
                return@mapIndexedNotNull null
            }

            idx to course
        }

        // 根据 VideoMode 决定课程遍历方式（等价 Go XueXiTongPart.go:125-138）
        when (account.coursesCustom.videoMode) {
            VideoMode.NORMAL -> {
                // 普通模式：课程串行，任务点串行
                filteredCourses.forEach { (idx, course) ->
                    val name = course.optString("courseName", "")
                    log("INFO", "[$name] 正在学习该课程")
                    onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                        totalCourses = courses.size, doneCourses = idx, currentCourse = name,
                        currentPhase = "[$name] 刷课中 (${idx+1}/${courses.size})"))
                    brushCourse(account, course, log)
                    onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                        totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name,
                        currentPhase = "[$name] 完毕"))
                    log("INFO", "[$name] 课程学习完毕")
                }
            }
            VideoMode.VIOLENT, VideoMode.DERED -> {
                // 多课程模式(2) / 多任务点模式(3)：课程并发（等价 Go 的 go func() 启动所有课程）
                coroutineScope {
                    filteredCourses.map { (idx, course) ->
                        launch {
                            val name = course.optString("courseName", "")
                            runCatching {
                                log("INFO", "[$name] 正在学习该课程")
                                onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                                    totalCourses = courses.size, doneCourses = idx, currentCourse = name,
                                    currentPhase = "[$name] 刷课中 (${idx+1}/${courses.size})"))
                                brushCourse(account, course, log)
                                onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                                    totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name,
                                    currentPhase = "[$name] 完毕"))
                                log("INFO", "[$name] 课程学习完毕")
                            }.onFailure { e ->
                                android.util.Log.e("YatoriXXT", "course[$name] exception", e)
                                log("ERROR", "[$name] 课程异常: ${e.message}")
                            }
                        }
                    }.joinAll()
                }
            }
            VideoMode.NONE -> {
                log("INFO", "VideoMode=不刷，跳过所有课程")
            }
        }
        log("INFO", "所有待学习课程学习完毕")
    }

    // ─── 登录（等价 Go XueXiTLoginAction）────────────────────────────────────────

    /** 等价 Go LoginApi 的 AES-CBC 加密：Key = IV = "u2oh6Vu^HWe4_AES" */
    private fun aesEncryptLogin(plain: String): String {
        val keyBytes = "u2oh6Vu^HWe4_AES".toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.IvParameterSpec(keyBytes))
        return android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray()), android.util.Base64.NO_WRAP)
    }

    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("fid", "-1")
            .add("uname", aesEncryptLogin(account.account))    // 等价 Go phoneEncrypted
            .add("password", aesEncryptLogin(account.password)) // 等价 Go passwdEncrypted
            .add("refer", "http%3A%2F%2Fi.mooc.chaoxing.com")
            .add("t", "true")
            .add("forbidotherlogin", "0")
            .add("validate", "")
            .add("doubleFactorLogin", "0")
            .add("independentId", "0")
            .add("independentNameId", "0")
            .build()
        val resp = client.newCall(
            Request.Builder().url("https://passport2.chaoxing.com/fanyalogin").post(body).build()
        ).execute()
        val json = safeJson(resp.body?.string())
            ?: throw RuntimeException("学习通登录失败：服务器返回非JSON（可能是账号已在其他地方登录或需要验证）")
        if (json.optBoolean("status") != true)
            throw RuntimeException("学习通登录失败: ${json.optString("mes")}")

        // 获取 userId（从 cookie 中的 _uid）
        userId = resp.headers("Set-Cookie")
            .mapNotNull { c -> if (c.startsWith("_uid=")) c.substringAfter("_uid=").substringBefore(";") else null }
            .firstOrNull() ?: ""

        // 触发验证码时绕过
        checkAndPassVerification()

        // 用桌面 UA 访问主页，触发服务器设置 fanyamoocs/tl 等浏览器会话 cookie（bot/index 需要它们才返回 cozeEnc）
        val pcUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
        runCatching {
            client.newCall(Request.Builder().url("https://i.chaoxing.com")
                .header("User-Agent", pcUA).build()
            ).execute().body?.close()
            // 访问实际 HTML 登录页以触发 fanyamoocs 等浏览器 cookie
            client.newCall(Request.Builder()
                .url("https://passport2.chaoxing.com/login?fid=&refer=http%3A%2F%2Fi.mooc.chaoxing.com&t=true&topURL=http%3A%2F%2Fi.mooc.chaoxing.com")
                .header("User-Agent", pcUA).build()
            ).execute().body?.close()
        }
    }

    private suspend fun reLogin(account: Account) { runCatching { login(account) } }

    // ─── 课程列表（GET mycourse/backclazzdata）────────────────────────────────────

    private suspend fun fetchCourses(account: Account): List<JSONObject> = withContext(Dispatchers.IO) {
        var resp = client.newCall(
            Request.Builder().url("https://mooc1-api.chaoxing.com/mycourse/backclazzdata").build()
        ).execute()
        var body = resp.body?.string() ?: "{}"

        // 触发验证码处理（等价 Go 验证码绕过逻辑）
        if (body.contains("输入验证码")) {
            checkAndPassVerification()
            resp = client.newCall(
                Request.Builder().url("https://mooc1-api.chaoxing.com/mycourse/backclazzdata").build()
            ).execute()
            body = resp.body?.string() ?: "{}"
        }

        val json = safeJson(body) ?: run {
            return@withContext emptyList()
        }
        val channels = json.optJSONArray("channelList") ?: run {
            return@withContext emptyList()
        }
        (0 until channels.length()).mapNotNull { i ->
            val ch = channels.getJSONObject(i)
            val cpi = ch.optString("cpi", "")  // cpi 在 channel 层
            val content = ch.optJSONObject("content") ?: return@mapNotNull null
            val courseData = content.optJSONObject("course")?.optJSONArray("data")
                ?: return@mapNotNull null
            if (courseData.length() == 0) return@mapNotNull null
            val c = courseData.getJSONObject(0)
            // classId/courseId 从 courseSquareUrl 里截取，等价 Go strings.Split 逻辑
            val squareUrl = c.optString("courseSquareUrl", "")
            val classId = if (squareUrl.contains("classId="))
                squareUrl.substringAfter("classId=").substringBefore("&")
            else content.optString("id")  // fallback
            val courseId = if (squareUrl.contains("courseId="))
                squareUrl.substringAfter("courseId=").substringBefore("&")
            else c.optString("id")  // fallback
            JSONObject().apply {
                put("classId", classId)
                put("courseId", courseId)
                put("courseName", c.optString("name"))
                put("cpi", cpi)
            }
        }
    }

    // ─── 单课程刷取 ───────────────────────────────────────────────────────────────

    private suspend fun brushCourse(account: Account, course: JSONObject, log: suspend (String, String) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val classId = course.optString("classId")
        val courseId = course.optString("courseId")
        val cpi = course.optString("cpi")

        // 拉取章节目录（等价 Go FetchChapterCords — 使用 gas/clazz 接口）
        val ts = System.currentTimeMillis()
        val url = "https://mooc1-api.chaoxing.com/gas/clazz" +
            "?id=${classId}&personid=${cpi}" +
            "&fields=id,bbsid,classscore,isstart,iscopy,classClosed,allowdownload,chatid,cpi,iswx,popupadd,recordstatus,coursesetting.fields(id,courseid,hiddenstatus,selectedvideoid,allow_tv,copy_level,copy_url,open_time,open_type,open_info,selfedit,coursetype),course.fields(id,name,imageurl,teacherfactor,knowledge.fields(id,name,indexOrder,parentnodeid,status,isReview,layer,label,jobcount,begintime,endtime,attachment.fields(id,type,objectid,extension).type(video)))" +
            "&view=json"
        val chapResp = client.newCall(Request.Builder().url(url).build()).execute()
        val chapBody = chapResp.body?.string() ?: return@withContext

        if (chapResp.code == 500) { reLogin(account); return@withContext }
        if (chapBody.contains("触发验证码") || chapBody.contains("输入验证码")) {
            checkAndPassVerification()
            return@withContext
        }

        val chapJson = safeJson(chapBody) ?: run { reLogin(account); return@withContext }
        // 响应路径: data[0].course.data[0].knowledge.data[]
        val knowledge = chapJson.optJSONArray("data")
            ?.optJSONObject(0)
            ?.optJSONObject("course")
            ?.optJSONArray("data")
            ?.optJSONObject(0)
            ?.optJSONObject("knowledge")
            ?.optJSONArray("data")

        if (knowledge == null) {
            log("WARN", "获取章节知识树失败")
            return@withContext
        }

        val knowledgeIds = mutableListOf<String>()
        for (i in 0 until knowledge.length()) {
            val node = knowledge.getJSONObject(i)
            val id = node.optString("id", "")
            if (id.isNotEmpty() && node.optInt("jobcount", 0) > 0) knowledgeIds.add(id)
        }
        if (knowledgeIds.isEmpty()) return@withContext

        // 调用 myjobsnodesmap 获取每个节点的实际完成状态（等价 Go FetchChapterPointStatus）
        val ts2 = System.currentTimeMillis()
        val mapBody = FormBody.Builder()
            .add("view", "json")
            .add("nodes", knowledgeIds.joinToString(","))
            .add("clazzid", classId)
            .add("time", ts2.toString())
            .add("userid", userId)
            .add("cpi", cpi)
            .add("courseid", courseId)
            .build()
        val mapResp = client.newCall(
            Request.Builder().url("https://mooc1-api.chaoxing.com/job/myjobsnodesmap").post(mapBody).build()
        ).execute()
        val mapJson = safeJson(mapResp.body?.string())

        // 只保留未完成的节点；total=0 说明平台未返回数据，保守处理继续刷
        val unfinished = knowledgeIds.filter { id ->
            val nodeStatus = mapJson?.optJSONObject(id)
            if (nodeStatus == null) true
            else {
                val finish = nodeStatus.optInt("finishcount", 0)
                val total = nodeStatus.optInt("totalcount", 0)
                    .let { if (it == 0) nodeStatus.optInt("unfinishcount", 0) else it }
                total == 0 || finish < total  // total=0 视为未完成
            }
        }
        log("INFO", "未完成章节: ${unfinished.size}/${knowledgeIds.size}，开始刷取")
        if (unfinished.isEmpty()) return@withContext

        // 等价 Go：VideoModel==3 才并发，其余模式顺序处理
        val concurrency = if (account.coursesCustom.videoMode == VideoMode.DERED)
            account.coursesCustom.cxNode.coerceAtLeast(1) else 1
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        coroutineScope {
            unfinished.map { knowledgeId ->
                launch {
                    semaphore.withPermit {
                        runCatching {
                            brushChapter(account, classId, courseId, cpi, knowledgeId, log)
                        }.onFailure { e ->
                            android.util.Log.e("YatoriXXT", "chapter[$knowledgeId] exception", e)
                            log("ERROR", "章节[$knowledgeId]异常: ${e::class.simpleName}: ${e.message ?: e.toString()}")
                        }
                    }
                }
            }.joinAll()
        }
    }

    private fun collectKnowledgeIds(node: JSONObject, result: MutableList<String>) {
        val id = node.optString("id", "")
        if (id.isNotEmpty() && node.optInt("jobUnfinishedCount", 1) > 0) result.add(id)
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) collectKnowledgeIds(children.getJSONObject(i), result)
    }

    // ─── 单章节刷取（拉 card 列表 → 遍历提交视频）──────────────────────────────

    private suspend fun brushChapter(
        account: Account, classId: String, courseId: String, cpi: String, knowledgeId: String,
        log: suspend (String, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // 等价 Go ChapterFetchCardsAction：遍历所有 card 页（num=0,1,2...）直到服务器不再返回 mArg
        var num = 0
        while (true) {
            val url = "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards" +
                "?clazzid=$classId&courseid=$courseId&knowledgeid=$knowledgeId" +
                "&num=$num&ut=s&cpi=$cpi&v=2025-0424-1038-3&mooc2=1&isMicroCourse=false&editorPreview=0"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val html = resp.body?.string() ?: break

            if (resp.code == 500) { reLogin(account); break }
            if (html.contains("触发验证码") || html.contains("输入验证码")) {
                checkAndPassVerification(); break
            }

            val match = Regex("""mArg\s*=\s*([^;]{6,})""").find(html) ?: break
            val cardJson = runCatching { JSONObject(match.groupValues[1]) }.getOrNull() ?: break
            val attachments = cardJson.optJSONArray("attachments") ?: break
            if (attachments.length() == 0) break

            for (i in 0 until attachments.length()) {
                val att = attachments.getJSONObject(i)
                val attType = att.optString("type", "")
                val isPassed = att.optBoolean("isPassed", false)
                val jtoken = att.optString("jtoken", "")
                val property = att.optJSONObject("property")
                val jobId = property?.optString("jobid", property.optString("_jobid", "")) ?: att.optString("jobid", "")
                val objectId = property?.optString("objectid", "") ?: ""
                if (isPassed) continue

                when (attType) {
                    "video", "audio" -> {
                        val fid = property?.optInt("fid", 0) ?: 0
                        if (jobId.isEmpty() || objectId.isEmpty()) continue
                        val meta = fetchVideoMeta(objectId, fid) ?: continue
                        val otherInfo = att.optString("otherInfo", "").split("&").firstOrNull() ?: ""
                        log("INFO", "提交${if(attType=="video") "视频" else "音频"} jobId=$jobId 时长${meta.duration}s")
                        submitVideo(account, classId, courseId, cpi, knowledgeId, jobId, objectId, meta.dtoken, meta.duration, otherInfo, log)
                    }
                    "document", "insertdoc", "insertbook" -> {
                        if (jobId.isEmpty()) continue
                        submitDocument(classId, courseId, knowledgeId, jobId, jtoken)
                    }
                    "insertread" -> {
                        if (jobId.isEmpty()) continue
                        submitReadV2(classId, courseId, knowledgeId, jobId, jtoken)
                    }
                    "hyperlink" -> {
                        if (jobId.isEmpty()) continue
                        submitHyperlink(classId, courseId, knowledgeId, jobId, jtoken)
                    }
                    "work", "workid" -> {
                        if (!account.coursesCustom.cxChapterTestSw) continue
                        if (account.coursesCustom.autoExam == ExamMode.OFF) continue
                        val workId = property?.optString("workid", "") ?: ""
                        val enc = att.optString("enc", "")
                        val ktoken = cardJson.optJSONObject("defaults")?.optString("ktoken", "") ?: ""
                        if (workId.isEmpty() || enc.isEmpty()) continue
                        submitChapterWork(classId, courseId, cpi, workId, enc, ktoken, jobId, knowledgeId, log)
                    }
                    else -> {}
                }
            }
            num++
        }
    }

    // ─── 文档/阅读任务点提交 ─────────────────────────────────────────────────────

    private suspend fun submitDocument(
        classId: String, courseId: String, knowledgeId: String,
        jobId: String, jtoken: String
    ) = withContext(Dispatchers.IO) {
        val dc = System.currentTimeMillis()
        val url = "https://mooc1.chaoxing.com/ananas/job/document" +
            "?jobid=$jobId&knowledgeid=$knowledgeId&courseid=$courseId&clazzid=$classId" +
            "&jtoken=$jtoken&_dc=$dc"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        resp.body?.string()
    }

    private suspend fun submitReadV2(
        classId: String, courseId: String, knowledgeId: String,
        jobId: String, jtoken: String
    ) = withContext(Dispatchers.IO) {
        val dc = System.currentTimeMillis()
        val url = "https://mooc1-api.chaoxing.com/ananas/job/readv2" +
            "?jobid=$jobId&knowledgeid=$knowledgeId&courseid=$courseId&clazzid=$classId" +
            "&jtoken=$jtoken&checkMicroTopic=true&microTopicId=0&_dc=$dc"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val body = resp.body?.string() ?: ""
    }

    private suspend fun submitHyperlink(
        classId: String, courseId: String, knowledgeId: String,
        jobId: String, jtoken: String
    ) = withContext(Dispatchers.IO) {
        val dc = System.currentTimeMillis()
        val url = "https://mooc1.chaoxing.com/ananas/job/hyperlink" +
            "?jobid=$jobId&knowledgeid=$knowledgeId&courseid=$courseId&clazzid=$classId" +
            "&jtoken=$jtoken&checkMicroTopic=true&microTopicId=undefined&_dc=$dc"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val body = resp.body?.string() ?: ""
    }

    // ─── 获取 dtoken+duration（GET ananas/status/{objectId}）──────────────────

    data class VideoMeta(val dtoken: String, val duration: Int)

    private suspend fun fetchVideoMeta(objectId: String, fid: Int): VideoMeta? = withContext(Dispatchers.IO) {
        val dc = System.currentTimeMillis()
        val resp = client.newCall(
            Request.Builder().url("https://mooc1-api.chaoxing.com/ananas/status/$objectId?k=$fid&flag=normal&_dc=$dc").build()
        ).execute()
        val body = resp.body?.string()
        val json = safeJson(body) ?: return@withContext null
        val dtoken = json.optString("dtoken", "").ifEmpty { return@withContext null }
        val duration = json.optInt("duration", 0)
        VideoMeta(dtoken, duration)
    }

    // ─── 视频学时提交（等价 Go VideoSubmitStudyTimeAction）──────────────────────

    private suspend fun submitVideo(
        account: Account, classId: String, courseId: String, cpi: String,
        knowledgeId: String, jobId: String, objectId: String, dtoken: String,
        duration: Int, otherInfo: String, log: suspend (String, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (duration <= 0) return@withContext

        // 每58秒提交一次（等价 Go flag==58 循环）
        var playingTime = 0
        while (playingTime < duration) {
            playingTime = (playingTime + 58).coerceAtMost(duration)
            val clipTime = "0_$duration"
            // enc = md5("[classId][userId][jobId][objectId][playingTime*1000][d_yHJ!$pdA~5][duration*1000][clipTime]")
            val encSrc = "[$classId][$userId][$jobId][$objectId][${playingTime * 1000}][d_yHJ!\$pdA~5][${duration * 1000}][$clipTime]"
            val enc = md5(encSrc)
            val ts = System.currentTimeMillis()
            val submitUrl = "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/$cpi/$dtoken" +
                "?clazzId=$classId&playingTime=$playingTime&duration=$duration" +
                "&clipTime=$clipTime&objectId=$objectId&otherInfo=$otherInfo" +
                "&courseId=$courseId&jobid=$jobId&userid=$userId" +
                "&isdrag=0&view=json&enc=$enc&dtype=Video&_t=$ts&attDuration=$duration"

            val resp = client.newCall(Request.Builder().url(submitUrl).build()).execute()
            val body = resp.body?.string() ?: ""

            when (resp.code) {
                403, 404 -> {
                    // 触发人脸识别（等价 Go PassFacePhoneAction）
                    handleFaceChallenge(classId, courseId, cpi, knowledgeId, jobId, objectId)
                }
                202, 500 -> reLogin(account)
                else -> {
                    val json = safeJson(body)
                    if (json == null) { reLogin(account); break }
                    if (json.optBoolean("isPassed", false)) break
                }
            }
            if (playingTime < duration) delay(58_000L)
        }
    }

    // ─── 验证码绕过（等价 Go processVerify.ac OCR）───────────────────────────────

    private suspend fun checkAndPassVerification() = withContext(Dispatchers.IO) {
        repeat(5) {
            val imgResp = client.newCall(
                Request.Builder().url("https://mooc1-api.chaoxing.com/processVerifyPng.ac?t=${System.currentTimeMillis()}").build()
            ).execute()
            val bytes = imgResp.body?.bytes() ?: return@repeat
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@repeat
            val cols = if (bmp.width == 140) 23 else 30
            val code = ocrEngine.recognizeSemi(bmp, cols)

            val passBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("app", "0").addFormDataPart("ucode", code).build()
            val passResp = client.newCall(
                Request.Builder().url("https://mooc1-api.chaoxing.com/html/processVerify.ac")
                    .post(passBody).build()
            ).execute()
            if (passResp.code == 302) return@withContext
        }
    }

    // ─── 人脸绕过（等价 Go PassFacePhoneAction）─────────────────────────────────

    private suspend fun handleFaceChallenge(
        classId: String, courseId: String, cpi: String,
        knowledgeId: String, jobId: String, objectId: String
    ) = withContext(Dispatchers.IO) {
        // 拉取历史人脸图片
        val faceResp = client.newCall(
            Request.Builder().url("https://mooc1-api.chaoxing.com/exam-ans/exam/phone/lookFacePhoto?courseid=$courseId&clazzid=$classId&cpi=$cpi").build()
        ).execute()
        val faceBytes = faceResp.body?.bytes() ?: return@withContext
        val faceBmp = BitmapFactory.decodeByteArray(faceBytes, 0, faceBytes.size) ?: return@withContext
        val disturbed = faceBypassEngine.disturb(faceBmp)

        // 获取上传 token
        val tokenResp = client.newCall(
            Request.Builder().url("https://mooc1-api.chaoxing.com/exam-ans/exam/phone/uploaFaceToken").build()
        ).execute()
        val tokenJson = safeJson(tokenResp.body?.string()) ?: return@withContext
        val uploadToken = tokenJson.optString("_token", "")

        // 上传人脸
        val baos = java.io.ByteArrayOutputStream()
        disturbed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
        val imgBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("token", uploadToken)
            .addFormDataPart("file", "face.jpg",
                baos.toByteArray().toRequestBody("image/jpeg".toMediaType()))
            .build()
        val uploadResp = client.newCall(
            Request.Builder().url("https://up.qbox.me/").post(imgBody).build()
        ).execute()
        val objectId2 = safeJson(uploadResp.body?.string())?.optString("key", "") ?: ""

        // 提交人脸
        val passBody = FormBody.Builder()
            .add("classId", classId).add("courseId", courseId)
            .add("chapterId", knowledgeId).add("cpi", cpi).add("objectId", objectId2).build()
        client.newCall(
            Request.Builder().url("https://mooc1-api.chaoxing.com/exam-ans/exam/phone/passCourseFacenew")
                .post(passBody).build()
        ).execute()

        delay(2_000L)
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5")
        return BigInteger(1, d.digest(s.toByteArray())).toString(16).padStart(32, '0')
    }

    // ─── 章节测验（workid）XXT 内置 AI 答题 ──────────────────────────────────────

    private data class WorkQuestion(
        val id: String, val typeCode: String, val score: String,
        val text: String, val options: Map<String, String>
    )

    private data class XxtAiParams(
        val cozeEnc: String, val userId: String, val courseId: String,
        val classId: String, val conversationId: String,
        val courseName: String, val studentName: String, val personId: String
    )

    private suspend fun submitChapterWork(
        classId: String, courseId: String, cpi: String,
        workId: String, enc: String, ktoken: String, jobId: String, knowledgeId: String,
        log: suspend (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 等价 Go lockByCourse：同一课程的AI调用串行化，lock()/unlock() 避免 withLock lambda 的 return 限制
        val mutex = aiCourseLocks.getOrPut(courseId) { kotlinx.coroutines.sync.Mutex() }
        mutex.lock()
        try {
        // Primary: android/mworkspecial；needRedirect=true 可能重定向到 stat2-ans.chaoxing.com/bot/index?cozeEnc=xxx
        val (primaryHtml, finalUrl) = fetchWorkHtmlPrimary(courseId, classId, cpi, workId, enc, ktoken, jobId, knowledgeId)
        // 从重定向URL提取 cozeEnc（等价 Go 通过 mworkspecial→bot/index 重定向拿到 cozeEnc）
        val cozeEncFromRedirect = finalUrl?.let {
            if (it.contains("stat2-ans.chaoxing.com")) {
                it.toHttpUrlOrNull()?.queryParameter("cozeEnc").orEmpty()
            } else ""
        } ?: ""
        var paperHtml = if (primaryHtml == null || primaryHtml.contains("无效的权限,code=2"))
            fetchWorkHtmlFallback(courseId, classId, cpi, workId, enc, jobId, knowledgeId)
        else primaryHtml
        if (paperHtml == null) { log("WARN", "章节测验[$workId] 获取试卷失败"); return@withContext }

        val doc = org.jsoup.Jsoup.parse(paperHtml)
        val t = doc.text()
        if (t.contains("已截止，不能作答")) { log("WARN", "章节测验[$workId] 已截止"); return@withContext }
        // 检查 doHomeWork 页面里是否直接有 cozeEnc
        val cozeEncInPaper = doc.selectFirst("input#cozeEnc")?.attr("value") ?: ""

        // 等价 Go ParseWorkInform 完整字段提取
        val answerId = doc.selectFirst("input#answerId")?.`val`() ?: doc.selectFirst("input#workAnswerId")?.`val`() ?: ""
        if (answerId.isEmpty()) { log("WARN", "章节测验[$workId] answerId为空，可能已完成"); return@withContext }
        val encWork = doc.selectFirst("input#encWork")?.`val`() ?: doc.selectFirst("input#enc_work")?.`val`() ?: ""
        val totalQuestionNum = doc.selectFirst("input#totalQuestionNum")?.`val`() ?: ""
        val fullScore = doc.selectFirst("input#fullScore")?.`val`() ?: ""
        val apiField = doc.selectFirst("input#api")?.`val`() ?: ""
        val oldSchoolId = doc.selectFirst("input#oldSchoolId")?.`val`() ?: ""
        val oldWorkId2 = doc.selectFirst("input#oldWorkId")?.`val`() ?: workId
        val jobidField = doc.selectFirst("input#jobid")?.`val`() ?: jobId
        val workRelationId = doc.selectFirst("input#workRelationId")?.`val`() ?: doc.selectFirst("input#workId")?.`val`() ?: workId
        val workAnswerId = doc.selectFirst("input#workAnswerId")?.`val`() ?: answerId

        val questions = parseAllWorkQuestions(doc)
        if (questions.isEmpty()) { log("WARN", "章节测验[$workId] 未解析到题目"); return@withContext }

        val ai = fetchXxtAiParams(classId, courseId, cpi).let { params ->
            if (cozeEncFromRedirect.isNotEmpty() && params != null) params.copy(cozeEnc = cozeEncFromRedirect)
            else params
        } ?: run { log("WARN", "章节测验[$workId] AI参数获取失败"); return@withContext }
        log("INFO", "【章节测验】共${questions.size}题，内置AI作答中...")

        // 先收集所有答案，再一次性提交（等价 Go WorkNewSubmitAnswer - addStudentWorkNew）
        val allAnswers = questions.map { q ->
            val answers = runCatching { callXxtAi(ai, buildWorkPrompt(q)) }.getOrDefault(emptyList())
            log("INFO", "【章节测验】第${questions.indexOf(q)+1}/${questions.size}题 AI回答 ${answers.joinToString()}")
            Pair(q, answers)
        }

        // 等价 Go WorkNewSubmitAnswer：multipart 一次性提交全部答案
        submitAllWorkAnswers(
            courseId, classId, cpi, answerId, workAnswerId, encWork,
            totalQuestionNum, fullScore, apiField, oldSchoolId, oldWorkId2,
            jobidField, workRelationId, allAnswers
        )
        log("INFO", "【章节测验】所有题目AI作答完毕，已提交")
        } finally { mutex.unlock() }
    }

    // 等价 Go WorkNewSubmitAnswer：multipart 一次提交全部答案到 addStudentWorkNew
    private suspend fun submitAllWorkAnswers(
        courseId: String, classId: String, cpi: String,
        answerId: String, workAnswerId: String, encWork: String,
        totalQuestionNum: String, fullScore: String, api: String,
        oldSchoolId: String, oldWorkId: String, jobid: String, workRelationId: String,
        questionsWithAnswers: List<Pair<WorkQuestion, List<String>>>
    ) = withContext(Dispatchers.IO) {
        val boundary = "----FormBoundary${System.currentTimeMillis()}"
        val body = buildString {
            fun field(name: String, value: String) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("pyFlag", "")  // "" = 直接交卷，等价 Go isSubmit=true → submitState=""
            field("courseId", courseId); field("classId", classId); field("api", api)
            field("workAnswerId", workAnswerId); field("answerId", answerId)
            field("totalQuestionNum", totalQuestionNum); field("fullScore", fullScore)
            field("knowledgeid", "0"); field("oldSchoolId", oldSchoolId)
            field("oldWorkId", oldWorkId); field("jobid", jobid)
            field("workRelationId", workRelationId); field("enc", ""); field("enc_work", encWork)
            field("userId", userId); field("cpi", cpi)
            field("workTimesEnc", ""); field("randomOptions", "false"); field("isAccessibleCustomFid", "0")
            val answerwqbid = questionsWithAnswers.joinToString(",") { it.first.id } + ","
            questionsWithAnswers.forEach { (q, answers) ->
                // 兜底：若答案为空或AI拒绝作答，随机选一个选项（等价 Go AnswerFixedPattern 的兜底逻辑）
                val letters = if (q.options.isNotEmpty()) q.options.keys.toList() else listOf("A","B","C","D")
                fun validLetter(s: String) = s.length <= 2 && s.uppercase().all { it in 'A'..'Z' }
                val raw = answers.firstOrNull() ?: ""
                val fallback = letters.random()
                val answer = if (validLetter(raw)) raw else fallback
                when (q.typeCode) {
                    "0" -> { field("answer${q.id}", answer); field("answertype${q.id}", "0") }
                    "1" -> {
                        val multi = answers.filter { validLetter(it) }.joinToString("").ifEmpty { fallback }
                        field("answer${q.id}", multi); field("answertype${q.id}", "1")
                    }
                    "3" -> {
                        val v = if (raw.contains("对") || raw.equals("true", true) || raw == "A") "true" else "false"
                        field("answer${q.id}", v); field("answertype${q.id}", "3")
                    }
                    "2" -> {
                        answers.ifEmpty { listOf(fallback) }.forEachIndexed { i, a -> field("answer${q.id}${i+1}", a) }
                        field("tiankongsize${q.id}", answers.size.coerceAtLeast(1).toString()); field("answertype${q.id}", "2")
                    }
                    else -> { field("answer${q.id}", raw.ifEmpty { fallback }); field("answertype${q.id}", q.typeCode) }
                }
            }
            field("answerwqbid", answerwqbid)
            append("--$boundary--\r\n")
        }
        val urlStr = "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew" +
            "?_classId=$classId&courseid=$courseId&token=${encWork}&totalQuestionNum=$totalQuestionNum" +
            "&ua=pc&formType=post&saveStatus=1&version=1&tempsave="
        val result = runCatching {
            client.newCall(Request.Builder().url(urlStr)
                .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                .header("X-Requested-With", "com.chaoxing.mobile").build()
            ).execute().body?.string()
        }.getOrNull()
    }

    // 等价 Go WorkFetchQuestion：android/mworkspecial
    // 返回 Pair(html, finalUrl) — needRedirect=true 可能重定向到 stat2-ans.chaoxing.com/bot/index?cozeEnc=xxx
    private suspend fun fetchWorkHtmlPrimary(
        courseId: String, classId: String, cpi: String,
        workId: String, enc: String, ktoken: String, jobId: String, knowledgeId: String
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val url = "https://mooc1-api.chaoxing.com/android/mworkspecial" +
            "?courseid=$courseId&workid=$workId&jobid=$jobId&needRedirect=true" +
            "&knowledgeid=$knowledgeId&userid=$userId&ut=s&clazzId=$classId" +
            "&cpi=$cpi&ktoken=$ktoken&enc=$enc"
        runCatching {
            val resp = client.newCall(Request.Builder().url(url)
                .header("X-Requested-With", "com.chaoxing.mobile").build()
            ).execute()
            val finalUrl = resp.request.url.toString()
            Pair(resp.body?.string(), finalUrl)
        }.getOrDefault(Pair(null, null))
    }

    // 等价 Go WorkFetch2Question：mooc-ans/work/phone/work
    private suspend fun fetchWorkHtmlFallback(
        courseId: String, classId: String, cpi: String,
        workId: String, enc: String, jobId: String, knowledgeId: String
    ): String? = withContext(Dispatchers.IO) {
        val url = "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/work" +
            "?workId=$workId&courseId=$courseId&clazzId=$classId" +
            "&knowledgeId=$knowledgeId&jobId=&enc=$enc&cpi=$cpi&originJobId=$jobId"
        runCatching {
            client.newCall(Request.Builder().url(url)
                .header("X-Requested-With", "com.chaoxing.mobile").build()
            ).execute().body?.string()
        }.getOrNull()
    }

    // 等价 Go ParseQuestionSets + per-question parsing：div.Py-mian1[data] 每元素一题
    private fun parseAllWorkQuestions(doc: org.jsoup.nodes.Document): List<WorkQuestion> {
        val typeMap = mapOf("多选" to "1", "判断" to "3", "辨析" to "3", "填空" to "2",
            "简答" to "4", "论述" to "6", "投票" to "0")
        return doc.select("div.Py-mian1[data]").mapNotNull { block ->
            val qId = block.attr("data").ifEmpty { return@mapNotNull null }
            val typeText = block.selectFirst(".Py-m1-title .quesType")?.text() ?: ""
            val typeCode = typeMap.entries.firstOrNull { typeText.contains(it.key) }?.value ?: "0"
            val qText = block.selectFirst(".Py-m1-title .workTextWrap")?.text()?.trim() ?: ""
            val score = block.selectFirst("input[name=score$qId]")?.`val`() ?: "0"
            val options = linkedMapOf<String, String>()
            block.select(".answerList li").forEach { li ->
                val letter = li.selectFirst("em.choose-opt")?.let { em ->
                    em.attr("id-param").ifEmpty { em.text() }
                } ?: li.selectFirst("em")?.text() ?: return@forEach
                val text = li.selectFirst("cc")?.text()?.ifEmpty { li.ownText() } ?: li.ownText()
                if (letter.isNotBlank()) options[letter.trim()] = text.trim()
            }
            WorkQuestion(qId, typeCode, score, qText, options)
        }
    }

    private fun buildWorkPrompt(q: WorkQuestion): String {
        val typeName = when (q.typeCode) {
            "1" -> "多选题"; "3" -> "判断题"; "2" -> "填空题"; "4" -> "简答题"; "6" -> "论述题"; else -> "单选题"
        }
        return buildString {
            append("[$typeName] ${q.text}")
            q.options.forEach { (k, v) -> append("\n${k}、$v") }
            append("\n请以JSON数组回答，如[\"A\"]、[\"A\",\"C\"]、[\"正确\"]或[\"答案\"]")
        }
    }

    private suspend fun fetchXxtAiParams(classId: String, courseId: String, cpi: String): XxtAiParams? {
        // 用 WebView（真实浏览器JS引擎）加载 bot/index，同步已有 cookies，让服务器返回含 cozeEnc 的桌面版 HTML
        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val pcUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
                val url = "https://stat2-ans.chaoxing.com/bot/index?fromWorkbench=true&upload=true&clazzid=$classId&showToolbox=false&bgColorNone=true&app_id=1192651262850&courseid=$courseId&cpi=$cpi&bot_id=7438777570621653018&ut=s"

                // 将 OkHttp cookie jar 里的 chaoxing.com cookies 同步到 WebView
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val jar = client.cookieJar.loadForRequest(
                    okhttp3.HttpUrl.Builder().scheme("https").host("stat2-ans.chaoxing.com").build()
                )
                jar.forEach { c -> cm.setCookie("https://stat2-ans.chaoxing.com", "${c.name}=${c.value}; path=/; domain=.chaoxing.com") }
                cm.flush()

                @android.annotation.SuppressLint("SetJavaScriptEnabled")
                val wv = android.webkit.WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.userAgentString = pcUA
                wv.settings.domStorageEnabled = true

                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        if (!cont.isActive) return  // 页面加载完但协程已取消，直接返回
                        // 提取 cozeEnc 等隐藏字段
                        view.evaluateJavascript("""(function(){
                            function v(id){var e=document.getElementById(id);return e?e.value:'';}
                            var sn='';
                            var m=document.body.innerHTML.match(/"studentName"\s*:\s*"([^"]+)"/);
                            if(m)sn=m[1];
                            return JSON.stringify({cozeEnc:v('cozeEnc'),userId:v('userId'),courseId:v('courseId'),clazzId:v('clazzId'),conversationId:v('conversationId'),courseName:v('courseName'),personId:v('personId'),studentName:sn});
                        })()""") { result ->
                            if (cont.isActive) {
                                val clean = result?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                                runCatching {
                                    val j = org.json.JSONObject(clean)
                                    val cozeEnc = j.optString("cozeEnc").ifEmpty {
                                        // WebView 仍是JSBridge版本 → 用 cookie 兜底
                                        jar.firstOrNull { it.name == "cx_p_token" }?.value ?: ""
                                    }
                                    XxtAiParams(
                                        cozeEnc = cozeEnc,
                                        userId = j.optString("userId").ifEmpty { userId },
                                        courseId = j.optString("courseId").ifEmpty { courseId },
                                        classId = j.optString("clazzId").ifEmpty { classId },
                                        conversationId = j.optString("conversationId"),
                                        courseName = j.optString("courseName"),
                                        studentName = j.optString("studentName"),
                                        personId = j.optString("personId")
                                    )
                                }.getOrNull().also { cont.resume(it) {} }
                            }
                        }
                    }
                }
                wv.loadUrl(url)
                cont.invokeOnCancellation { wv.stopLoading() }
            }
        }
    }

    private suspend fun callXxtAi(p: XxtAiParams, content: String): List<String> = withContext(Dispatchers.IO) {
        ensureActive()  // 检查协程是否已取消
        if (p.cozeEnc.isEmpty()) return@withContext emptyList()
        val reqBody = """[{"role":"user","content":${org.json.JSONObject.quote(content)},"baseData":{"conversationId":"${p.conversationId}","userId":"${p.userId}","appId":"1192651262850","botId":"7438777570621653018","custom_variables":{"courseName":"${p.courseName}","studentName":"${p.studentName}","weakKnowledgePoint":"{}"},"shortcut_command":{},"sourceInfo":"","sdkFlag":"false","courseid":"${p.courseId}","clazzid":"${p.classId}","personid":"${p.personId}"}}]"""
        val url = "https://stat2-ans.chaoxing.com/stat2/bot/talk-v1?cozeEnc=${p.cozeEnc}&botId=7438777570621653018&userId=${p.userId}&appId=1192651262850&courseid=${p.courseId}&clazzid=${p.classId}&ut=s"
        val resp = runCatching {
            aiClient.newCall(Request.Builder().url(url)
                .post(reqBody.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Origin", "https://stat2-ans.chaoxing.com")
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("Referer", "https://stat2-ans.chaoxing.com/bot/index?fromWorkbench=true&upload=true&clazzid=${p.classId}&showToolbox=false&bgColorNone=true&app_id=1192651262850&courseid=${p.courseId}&bot_id=7438777570621653018&ut=s")
                .build()
            ).execute()
        }.getOrElse { e -> android.util.Log.e("YatoriAI", "AI request failed", e); return@withContext emptyList() }
        val sb = StringBuilder()
        resp.body?.byteStream()?.bufferedReader()?.use { reader ->
            reader.lineSequence().forEach { line ->
                ensureActive()  // 每读一行检查取消状态
                line.split("\$_\$").forEach { part ->
                    val p2 = part.trim()
                    if (p2.isEmpty() || p2.startsWith("server-")) return@forEach
                    runCatching {
                        val j = org.json.JSONObject(p2)
                        if (j.optString("type") == "coreAnswer") sb.append(j.optString("content"))
                    }
                }
            }
        }
        val raw = sb.toString()
            .replace("&quot;", "\"").replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        // 解析 JSON 数组，兼容 ["C"] 和 [{"name":"C"}] 两种格式
        fun parseAnswerArray(jsonStr: String): List<String>? = runCatching {
            val arr = org.json.JSONArray(jsonStr)
            (0 until arr.length()).map { i ->
                val item = arr.get(i)
                if (item is org.json.JSONObject) item.optString("name", item.optString("value", ""))
                else item.toString()
            }.filter { it.isNotEmpty() }
        }.getOrNull()

        parseAnswerArray(raw)
            ?: Regex("""\[[^\[\]]+\]""").findAll(raw).lastOrNull()?.value?.let { parseAnswerArray(it) }
            ?: if (raw.isNotEmpty()) listOf(raw) else emptyList()
    }

    private suspend fun submitWorkAnswer(
        answerId: String, encWork: String, enc: String, source: String,
        courseId: String, classId: String, workId: String,
        q: WorkQuestion, answers: List<String>, index: Int, isLast: Boolean
    ) = withContext(Dispatchers.IO) {
        val b = FormBody.Builder()
            .add("workExamUploadUrl", "").add("workExamUploadCrcUrl", "")
            .add("workRelationAnswerId", answerId).add("knowledgeid", "0")
            .add("enc", enc).add("source", source).add("encWork", encWork)
            .add("courseId", courseId).add("workRelationId", workId).add("classId", classId)
            .add("courseId", courseId).add("workRelationId", workId).add("classId", classId)
            .add("workTimesEnc", "")
            .add("questionId", q.id).add("index", "$index").add("tempSave", "${!isLast}")
        b.add("type${q.id}", q.typeCode).add("score${q.id}", q.score)
        when (q.typeCode) {
            "1" -> b.add("answers${q.id}", answers.joinToString(""))
            "3" -> {
                val a = answers.firstOrNull() ?: ""
                b.add("answer${q.id}", if (a.contains("对") || a.equals("true", true) || a == "A") "true" else "false")
            }
            "2" -> {
                val blanks = answers.ifEmpty { listOf("") }
                blanks.forEachIndexed { i, v -> b.add("answer${q.id}${i+1}", v) }
                b.add("blankNum${q.id}", blanks.indices.joinToString(",") { "${it+1}" } + ",")
            }
            else -> b.add("answer${q.id}", answers.firstOrNull() ?: "")
        }
        val result = client.newCall(Request.Builder()
            .url("https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doNormalHomeWorkSubmit?tempSave=${!isLast}")
            .post(b.build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest").build()
        ).execute().body?.string()
    }
}
