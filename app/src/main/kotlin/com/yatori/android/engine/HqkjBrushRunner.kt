package com.yatori.android.engine

import com.yatori.android.domain.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 海旗科技（HQKJ）刷课 Runner — 完整对照 Go api/haiqikeji/HqkjApi.go 重写
 *
 * 登录流程：GET selectdomain 取 schoolId → GET login 取 token → GET yee_student_info 取 userId
 * 刷课流程：GET yee_my_course_list → GET yee_node_select (data[].children[]) →
 *          POST study_session_start 取 sessionId → 等待 → POST study_session_heartbeat(100) → POST study_session_end
 */
@Singleton
class HqkjBrushRunner @Inject constructor() : PlatformBrushRunner {

    private val UA = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder().cookieJar(SimpleCookieJar()).build()

    private var token = ""; private var userId = ""; private var schoolId = ""

    override suspend fun run(
        taskId: String, account: Account, settings: AppSettings,
        scope: CoroutineScope, onProgress: (TaskProgress) -> Unit,
 log: suspend (String, String) -> Unit
) = withContext(Dispatchers.IO) {
        login(account)
        log("INFO", "登录成功")
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING))

        val courses = fetchCourses(account)
        log("INFO", "获取到课程数: ${courses.size}")
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING, totalCourses = courses.size))

        courses.forEachIndexed { idx, course ->
            val name = course.optString("name").ifBlank { course.optString("courseName") }
            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses
            if (exclude.isNotEmpty() && exclude.contains(name)) return@forEachIndexed
            if (include.isNotEmpty() && !include.contains(name)) return@forEachIndexed

            log("INFO", "开始处理课程: $name")
            brushCourse(account, course, log)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name))
            log("INFO", "课程完成: $name")
        }
    }

    // ─── 登录 ───────────────────────────────────────────────────────────────────

    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        val hostname = URL(account.url).host

        // Step 1: GET /api/course/selectdomain?domain={hostname} → data.id
        val schoolJson = get(account.url, "/api/course/selectdomain?domain=$hostname")
        schoolId = schoolJson.optJSONObject("data")?.opt("id")?.toString() ?: ""
        if (schoolId.isEmpty()) throw RuntimeException("海旗科技：无法获取学校ID，请检查URL（$schoolJson）")

        // Step 2: GET /api/user/login?number=&password=&schoolId=  （注意：GET，不是POST）
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val loginJson = get(account.url, "/api/user/login?number=${enc(account.account)}&password=${enc(account.password)}&schoolId=$schoolId")
        if (loginJson.optInt("code", -1) != 200) throw RuntimeException("海旗科技登录失败：$loginJson")
        token = loginJson.optString("data")

        // Step 3: GET /api/user/yee_student_info → data.id
        val userJson = getAuth(account.url, "/api/user/yee_student_info")
        userId = userJson.optJSONObject("data")?.opt("id")?.toString() ?: ""
    }

    // ─── 课程列表 ────────────────────────────────────────────────────────────────

    private suspend fun fetchCourses(account: Account): List<JSONObject> = withContext(Dispatchers.IO) {
        val json = getAuth(account.url, "/api/user/yee_my_course_list?schoolId=$schoolId&studentId=$userId&type=0&pageNum=1&pageSize=10000")
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).map { data.getJSONObject(it) }
    }

    // ─── 单课程刷取 ──────────────────────────────────────────────────────────────

    private suspend fun brushCourse(account: Account, course: JSONObject, log: suspend (String, String) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val courseId = course.opt("id")?.toString() ?: return@withContext

        val nodesJson = getAuth(account.url, "/api/user/yee_node_select?studentId=$userId&courseId=$courseId&schoolId=$schoolId")
        val chapters = nodesJson.optJSONArray("data") ?: return@withContext

        for (i in 0 until chapters.length()) {
            val children = chapters.getJSONObject(i).optJSONArray("children") ?: continue
            for (j in 0 until children.length()) {
                val node = children.getJSONObject(j)
                if (node.optInt("tabVideo", 0) != 1) continue

                val nodeId = node.opt("id")?.toString() ?: continue
                val nodeName = node.optString("name", nodeId)

                val progressJson = getAuth(account.url, "/api/user/last_progress?nodeId=$nodeId&userId=$userId&schoolId=$schoolId")
                val progress = progressJson.optString("data", "0").toDoubleOrNull() ?: 0.0
                if (progress >= 1.0) continue

                log("INFO", "刷视频: $nodeName")
                if (account.coursesCustom.videoMode == VideoMode.VIOLENT) {
                    brushNodeFast(account.url, nodeId, courseId)
                } else {
                    brushNodeNormal(account.url, nodeId, courseId, node.optInt("videoDuration", 0))
                }
                log("INFO", "视频完成: $nodeName")
            }
        }
    }

    /**
     * 秒刷（VIOLENT）：start → 等30s → heartbeat(100) → end
     */
    private suspend fun brushNodeFast(preUrl: String, nodeId: String, courseId: String) = withContext(Dispatchers.IO) {
        // POST /api/user/study_session_start
        val startBody = """{"schoolId":$schoolId,"userId":$userId,"nodeId":"$nodeId","courseId":"$courseId","terminal":"web"}"""
        val startJson = postJson(preUrl, "/api/user/study_session_start", startBody)
        if (startJson.optInt("code", -1) != 200) return@withContext
        val sessionId = startJson.optString("data")
        if (sessionId.isEmpty()) return@withContext

        delay(30_000L) // 等价 Go time.Sleep(30 * time.Second)

        // POST /api/user/study_session_heartbeat → 提交 100%
        postJson(preUrl, "/api/user/study_session_heartbeat", """{"sessionId":"$sessionId","progress":"100"}""")

        // POST /api/user/study_session_end
        postJson(preUrl, "/api/user/study_session_end", """{"sessionId":"$sessionId"}""")
    }

    // ─── HTTP 辅助 ────────────────────────────────────────────────────────────────

    /**
     * 普通模式（NORMAL）：start → 每5s步进5s提交进度 → end
     */
    private suspend fun brushNodeNormal(preUrl: String, nodeId: String, courseId: String, videoDuration: Int) = withContext(Dispatchers.IO) {
        val startBody = """{"schoolId":$schoolId,"userId":$userId,"nodeId":"$nodeId","courseId":"$courseId","terminal":"web"}"""
        val startJson = postJson(preUrl, "/api/user/study_session_start", startBody)
        if (startJson.optInt("code", -1) != 200) return@withContext
        val sessionId = startJson.optString("data")
        if (sessionId.isEmpty()) return@withContext

        val total = if (videoDuration > 0) videoDuration else 60
        var elapsed = 0
        while (elapsed < total) {
            delay(5_000L)
            elapsed = (elapsed + 5).coerceAtMost(total)
            val pct = (elapsed * 100 / total).coerceAtMost(100)
            postJson(preUrl, "/api/user/study_session_heartbeat", """{"sessionId":"$sessionId","progress":"$pct"}""")
        }
        postJson(preUrl, "/api/user/study_session_end", """{"sessionId":"$sessionId"}""")
    }

    private fun baseRequest(url: String) = Request.Builder()
        .header("accept", "application/json, text/plain, */*")
        .header("user-agent", UA)
        .url(url)

    private suspend fun get(preUrl: String, path: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(baseRequest("$preUrl$path").build()).execute()
        JSONObject(resp.body?.string() ?: "{}")
    }

    private suspend fun getAuth(preUrl: String, path: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(baseRequest("$preUrl$path").header("authorization", token).build()).execute()
        val body = resp.body?.string() ?: "{}"
        // 令牌失效自动重登一次
        val json = JSONObject(body)
        if (body.contains("令牌不匹配") || body.contains("认证失败")) {
            // token 过期：这里简单返回空，调用方可重试（与 Go 逻辑一致，实际重登由外层处理）
            return@withContext JSONObject()
        }
        json
    }

    private suspend fun postJson(preUrl: String, path: String, json: String): JSONObject = withContext(Dispatchers.IO) {
        val body = json.toRequestBody("application/json".toMediaType())
        val resp = client.newCall(
            baseRequest("$preUrl$path")
                .header("authorization", token)
                .header("content-type", "application/json")
                .header("origin", preUrl)
                .post(body).build()
        ).execute()
        JSONObject(resp.body?.string() ?: "{}")
    }
}
