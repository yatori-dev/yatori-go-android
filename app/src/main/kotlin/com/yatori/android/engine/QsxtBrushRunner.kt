package com.yatori.android.engine

import com.yatori.android.domain.model.*
import com.yatori.android.engine.ocr.OcrEngine
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 青书学堂（QSXT）刷课 Runner — 完整对照 Go api/qingshuxuetang/ + aggregation/qingshuxuetang/ 重写
 *
 * 登录：POST account/getValidationCode → base64算术图 OCR识别计算 → POST account/login → token
 * 课程：GET course/mine → data[].periods[].courses[]
 * 节点：GET course/getCourseDetail → coursewareUrl → 递归 data[].nodes[]
 * 提交：POST behavior/studyRecordStart → serverRecordId → POST studyRecordContinue(end=true)
 *
 * Header: Authorization-QS + Device-Info-QS（等价 Go cache.Token）
 */
@Singleton
class QsxtBrushRunner @Inject constructor(
    private val ocrEngine: OcrEngine
) : PlatformBrushRunner {

    private val client = OkHttpClient.Builder().cookieJar(SimpleCookieJar()).build()
    private var token = ""

    private fun authHeaders(b: Request.Builder) = b
        .header("Authorization-QS", token)
        .header("Device-Info-QS", """{"appType":1,"appVersion":"25.10.0","clientType":2,"deviceName":"xiaomi MI 5X","netType":1,"osVersion":"8.1.0"}""")
        .header("User-Agent-QS", "QSXT")
        .header("User-Agent", "okhttp/4.2.2")

    override suspend fun run(
        taskId: String, account: Account, settings: AppSettings,
        scope: CoroutineScope, onProgress: (TaskProgress) -> Unit,
 log: suspend (String, String) -> Unit
) = withContext(Dispatchers.IO) {
        login(account)
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING))

        val courses = fetchCourses()
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING, totalCourses = courses.size))

        courses.forEachIndexed { idx, course ->
            val name = course.optString("courseName", course.optString("name"))
            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses
            if (exclude.isNotEmpty() && exclude.contains(name)) return@forEachIndexed
            if (include.isNotEmpty() && !include.contains(name)) return@forEachIndexed
            brushCourse(course)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name))
        }
    }

    /**
     * 等价 Go QsxtLoginAction：
     * 1. POST getValidationCode → base64图 OCR → 计算算术结果
     * 2. POST login (account + password + sessionId + calc结果)
     */
    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        repeat(5) attempt@{
            // Step 1: 获取算术验证码
            val codeBody = """{"recv":"${account.account}","validationType":3}"""
                .toRequestBody("application/json".toMediaType())
            val codeResp = client.newCall(
                Request.Builder().url("https://api.qingshuxuetang.com/v25_10/account/getValidationCode")
                    .post(codeBody).header("User-Agent", "okhttp/4.2.2").build()
            ).execute()
            val codeJson = JSONObject(codeResp.body?.string() ?: "{}")
            if (codeJson.optInt("hr", -1) != 0) return@attempt
            val sessionId = codeJson.optJSONObject("data")?.optString("sessionId") ?: return@attempt
            val base64 = codeJson.optJSONObject("data")?.optString("code") ?: return@attempt

            // Step 2: 图片 → 算术 OCR（等价 Go AutoDetectionForCalc + AutoCalc）
            val imgBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                ?: return@attempt
            val ocrText = ocrEngine.recognize(bmp).trim()  // 识别如 "3+5" 或 "12-4"
            val calcResult = evalArithmetic(ocrText) ?: return@attempt

            // Step 3: 登录
            val loginBody = """{"name":"${account.account}","password":"${account.password}","type":1,"validation":{"sessionId":"$sessionId","type":3,"userInput":"$calcResult"}}"""
                .toRequestBody("application/json".toMediaType())
            val loginResp = client.newCall(
                Request.Builder().url("https://api.qingshuxuetang.com/v25_10/account/login")
                    .post(loginBody).header("User-Agent", "okhttp/4.2.2").build()
            ).execute()
            val loginJson = JSONObject(loginResp.body?.string() ?: "{}")

            if (loginJson.optString("message", "").contains("图片验证码答案错误")) return@attempt
            token = loginJson.optJSONObject("data")?.optString("token") ?: return@attempt
            return@withContext  // 登录成功
        }
        if (token.isEmpty()) throw RuntimeException("青书学堂：登录失败，验证码多次识别错误")
    }

    /**
     * 课程列表：GET course/mine → data[].{classId,schoolId,periods[].{id,courses[].{id}}}
     * 等价 Go PullCourseListAction 解析逻辑
     */
    private suspend fun fetchCourses(): List<JSONObject> = withContext(Dispatchers.IO) {
        val resp = client.newCall(authHeaders(
            Request.Builder().url("https://api.qingshuxuetang.com/v25_10/course/mine")
        ).build()).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (json.optInt("hr", -1) != 0) return@withContext emptyList()
        val courses = mutableListOf<JSONObject>()
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        for (i in 0 until data.length()) {
            val project = data.getJSONObject(i)
            val classId = project.opt("classId")?.toString() ?: continue
            val schoolId = project.opt("schoolId")?.toString() ?: continue
            val periods = project.optJSONArray("periods") ?: continue
            for (j in 0 until periods.length()) {
                val period = periods.getJSONObject(j)
                val semesterId = period.opt("id")?.toString() ?: continue
                val periodCourses = period.optJSONArray("courses") ?: continue
                for (k in 0 until periodCourses.length()) {
                    val c = periodCourses.getJSONObject(k)
                    c.put("classId", classId); c.put("schoolId", schoolId); c.put("semesterId", semesterId)
                    if (c.optBoolean("allowLearn", true)) courses.add(c)
                }
            }
        }
        courses
    }

    private suspend fun brushCourse(course: JSONObject) = withContext(Dispatchers.IO) {
        val courseId = course.opt("id")?.toString() ?: return@withContext
        val classId = course.optString("classId"); val schoolId = course.optString("schoolId")
        val semesterId = course.optString("semesterId")

        // 获取课程详情 → coursewareUrl
        val detailResp = client.newCall(authHeaders(Request.Builder()
            .url("https://api.qingshuxuetang.com/v25_10/course/getCourseDetail?periodId=$semesterId&classId=$classId&schoolId=$schoolId&source=1&userClassId=0&courseId=$courseId"))
            .build()).execute()
        val detailJson = JSONObject(detailResp.body?.string() ?: "{}")
        if (detailJson.optInt("hr", -1) != 0) return@withContext
        val coursewareUrl = detailJson.optJSONObject("data")?.optString("coursewareUrl") ?: return@withContext

        // 拉取节点树
        val nodesResp = client.newCall(authHeaders(Request.Builder().url(coursewareUrl)).build()).execute()
        val nodesJson = JSONObject(nodesResp.body?.string() ?: "{}")
        if (nodesJson.optInt("hr", -1) != 0) return@withContext
        val chapters = nodesJson.optJSONArray("data") ?: return@withContext

        // 递归学习所有节点
        for (i in 0 until chapters.length()) {
            studyNodes(chapters.getJSONObject(i), course)
        }
    }

    /** 递归遍历节点树，等价 Go pullChapterAction + StartStudyTimeAction + SubmitStudyTimeAction */
    private suspend fun studyNodes(node: JSONObject, course: JSONObject): Unit = withContext(Dispatchers.IO) {
        val nodeType = node.optString("type", node.optString("nodeType"))
        val nodeId = node.optString("id", node.optString("NodeId"))

        when {
            nodeType == "chapter" -> {
                // 递归子节点
                val children = node.optJSONArray("nodes") ?: return@withContext
                for (i in 0 until children.length()) studyNodes(children.getJSONObject(i), course)
            }
            nodeType == "video" || nodeType == "html" || nodeType == "pdf" -> {
                val lastStudyTime = node.optInt("lastStudyTime", node.optInt("LastStudyTime", 0))
                val duration = node.optInt("duration", node.optInt("Duration", 0))
                // 已完成跳过
                if (duration > 0 && lastStudyTime >= duration) return@withContext

                val courseId = course.opt("id")?.toString() ?: return@withContext
                val classId = course.optString("classId"); val schoolId = course.optString("schoolId")
                val semesterId = course.optString("semesterId")

                // studyRecordStart → serverRecordId
                val startBody = """{"classId":${classId.toIntOrNull() ?: 0},"clientType":2,"contentId":"$nodeId","contentType":11,"courseId":"$courseId","learnPlanId":0,"pageType":2,"periodId":${semesterId.toIntOrNull() ?: 0},"position":0,"schoolId":${schoolId.toIntOrNull() ?: 0},"userClassId":0}"""
                val startResp = client.newCall(authHeaders(Request.Builder()
                    .url("https://api.qingshuxuetang.com/v25_10/behavior/studyRecordStart")
                    .post(startBody.toRequestBody("application/json".toMediaType())))
                    .build()).execute()
                val startJson = JSONObject(startResp.body?.string() ?: "{}")
                if (startJson.optInt("hr", -1) != 0) return@withContext
                val serverRecordId = startJson.optString("data")

                delay(1_000L)

                // studyRecordContinue end=true
                val endBody = """{"clientType":2,"contentType":11,"detectId":0,"end":true,"position":${duration.coerceAtLeast(1)},"schoolId":${schoolId.toIntOrNull() ?: 0},"serverRecordId":"$serverRecordId"}"""
                client.newCall(authHeaders(Request.Builder()
                    .url("https://api.qingshuxuetang.com/v25_10/behavior/studyRecordContinue")
                    .post(endBody.toRequestBody("application/json".toMediaType())))
                    .build()).execute()
                delay(500L)
            }
            else -> {
                // 其他类型子节点继续递归
                val children = node.optJSONArray("nodes") ?: return@withContext
                for (i in 0 until children.length()) studyNodes(children.getJSONObject(i), course)
            }
        }
    }

    /**
     * 简单算术表达式求值（等价 Go AutoCalc）
     * 支持格式：数字+运算符+数字，如 "3+5"、"12-4"、"3×5"
     */
    private fun evalArithmetic(expr: String): Int? {
        val clean = expr.replace(" ", "").replace("×", "*").replace("÷", "/")
        return runCatching {
            when {
                "+" in clean -> clean.split("+").let { it[0].toInt() + it[1].toInt() }
                "-" in clean -> clean.split("-").let { it[0].toInt() - it[1].toInt() }
                "*" in clean -> clean.split("*").let { it[0].toInt() * it[1].toInt() }
                "/" in clean -> clean.split("/").let { it[0].toInt() / it[1].toInt() }
                else -> clean.toIntOrNull()
            }
        }.getOrNull()
    }
}
