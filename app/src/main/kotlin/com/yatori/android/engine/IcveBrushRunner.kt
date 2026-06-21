package com.yatori.android.engine

import com.yatori.android.domain.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智慧职教（ICVE 资源库）刷课 Runner — 对照 Go api/icve/ + aggregation/icve/ 重写
 * 仅支持 Cookie 登录（密码字段填 Cookie 字符串，含 token + zhzj-Token）
 */
@Singleton
class IcveBrushRunner @Inject constructor() : PlatformBrushRunner {

    private val AES_KEY = "djekiytolkijduey".toByteArray()
    private val client = OkHttpClient.Builder().cookieJar(SimpleCookieJar())
        .followRedirects(true).build()

    private var token = ""; private var zykAccessToken = ""; private var userId = ""

    override suspend fun run(
        taskId: String, account: Account, settings: AppSettings,
        scope: CoroutineScope, onProgress: (TaskProgress) -> Unit,
        log: suspend (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        loginWithCookie(account.password)
        log("INFO", "登录成功，userId=$userId")
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING))

        val courses = fetchCourses()
        log("INFO", "获取到课程数: ${courses.size}")
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING, totalCourses = courses.size))

        courses.forEachIndexed { idx, course ->
            val name = course.optString("courseName")
            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses
            if (exclude.isNotEmpty() && exclude.contains(name)) return@forEachIndexed
            if (include.isNotEmpty() && !include.contains(name)) return@forEachIndexed

            log("INFO", "开始处理课程: $name")
            brushCourse(course, log)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name))
            log("INFO", "课程完成: $name")
        }
    }

    /** 等价 Go IcveCookieLogin */
    private suspend fun loginWithCookie(cookieStr: String) = withContext(Dispatchers.IO) {
        cookieStr.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size != 2) return@forEach
            when (kv[0].trim()) {
                "token"      -> token = kv[1].trim()
            }
        }
        if (token.isEmpty()) throw RuntimeException("ICVE：Cookie 中缺少 token 字段，请重新填写 Cookie")

        // passLogin → 获取 ZYKAccessToken（等价 Go IcveZYKAccessTokenApi）
        client.newCall(Request.Builder()
            .url("https://www.icve.com.cn/prod-api/uc/passLogin?token=$token").build()).execute()
        val zykResp = client.newCall(Request.Builder()
            .url("https://zyk.icve.com.cn/prod-api/auth/passLogin?token=$token").build()).execute()
        val zykJson = JSONObject(zykResp.body?.string() ?: "{}")
        zykAccessToken = zykJson.optJSONObject("data")?.optString("access_token") ?: ""
        if (zykAccessToken.isEmpty()) throw RuntimeException("ICVE：获取资源库 token 失败: $zykJson")

        // 获取 userId（等价 Go IcveZYKPullUserInfoApi）
        val info = client.newCall(Request.Builder()
            .url("https://zyk.icve.com.cn/prod-api/system/user/getInfo")
            .header("Authorization", "Bearer $zykAccessToken").build()).execute()
        userId = JSONObject(info.body?.string() ?: "{}").optJSONObject("user")?.optString("userId") ?: ""
    }

    /** GET myCourseList（等价 Go PullZykCourse2Api） */
    private suspend fun fetchCourses(): List<JSONObject> = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder()
            .url("https://zyk.icve.com.cn/prod-api/teacher/courseList/myCourseList?pageSize=100&pageNum=1&flag=1")
            .header("Authorization", "Bearer $zykAccessToken").build()).execute()
        val rows = JSONObject(resp.body?.string() ?: "{}").optJSONArray("rows")
            ?: return@withContext emptyList()
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private suspend fun brushCourse(course: JSONObject, log: suspend (String, String) -> Unit) = withContext(Dispatchers.IO) {
        val courseInfoId = course.optString("courseInfoId")

        val rootResp = client.newCall(Request.Builder()
            .url("https://zyk.icve.com.cn/prod-api/teacher/courseContent/studyMoudleList?courseInfoId=$courseInfoId")
            .header("Authorization", "Bearer $zykAccessToken").build()).execute()
        val rootBody = rootResp.body?.string() ?: return@withContext
        val roots = runCatching { JSONArray(rootBody) }
            .getOrElse { JSONObject(rootBody).optJSONArray("data") }
            ?: return@withContext

        for (i in 0 until roots.length()) {
            pullAndStudyNode(roots.getJSONObject(i), 1, courseInfoId, log)
        }
    }

    /** 递归拉取节点树并提交（等价 Go pullNode + SubmitZYKStudyTimeAction） */
    private suspend fun pullAndStudyNode(node: JSONObject, level: Int, courseInfoId: String, log: suspend (String, String) -> Unit): Unit = withContext(Dispatchers.IO) {
        val nodeId = node.optString("id")
        val fileType = node.optString("fileType")
        val nodeName = node.optString("name", nodeId)

        when (fileType) {
            "父节点", "子节点" -> {
                val nextLevel = if (fileType == "父节点") 1 else level + 1
                val childResp = client.newCall(Request.Builder()
                    .url("https://zyk.icve.com.cn/prod-api/teacher/courseContent/studyList?level=$nextLevel&parentId=$nodeId&courseInfoId=$courseInfoId")
                    .header("Authorization", "Bearer $zykAccessToken").build()).execute()
                val childBody = childResp.body?.string() ?: return@withContext
                val children = runCatching { JSONArray(childBody) }
                    .getOrElse { JSONObject(childBody).optJSONArray("data") }
                    ?: return@withContext
                for (i in 0 until children.length()) {
                    pullAndStudyNode(children.getJSONObject(i), nextLevel, courseInfoId, log)
                }
            }
            "mp4", "mp3" -> {
                val infoResp = client.newCall(Request.Builder()
                    .url("https://zyk.icve.com.cn/prod-api/teacher/courseContent/$nodeId")
                    .header("Authorization", "Bearer $zykAccessToken").build()).execute()
                val infoData = JSONObject(infoResp.body?.string() ?: "{}").optJSONObject("data")
                val fileUrl = infoData?.optString("urlShort") ?: return@withContext

                val durationResp = client.newCall(Request.Builder()
                    .url("https://upload.icve.com.cn/$fileUrl/status").build()).execute()
                val durationStr = JSONObject(durationResp.body?.string() ?: "{}").optJSONObject("args")?.optString("duration") ?: "0"
                val totalNum = parseDuration(durationStr)
                if (totalNum <= 0) return@withContext

                log("INFO", "刷视频: $nodeName (${totalNum}s)")
                submitStudy(courseInfoId, node.optString("parentId"), totalNum, nodeId, totalNum)
                log("INFO", "视频完成: $nodeName")
            }
            "pdf", "ppt", "pptx", "doc", "docx" -> {
                val infoResp = client.newCall(Request.Builder()
                    .url("https://zyk.icve.com.cn/prod-api/teacher/courseContent/$nodeId")
                    .header("Authorization", "Bearer $zykAccessToken").build()).execute()
                val infoData = JSONObject(infoResp.body?.string() ?: "{}").optJSONObject("data")
                val fileUrl = infoData?.optString("urlShort") ?: return@withContext

                val statusResp = client.newCall(Request.Builder()
                    .url("https://upload.icve.com.cn/$fileUrl/status").build()).execute()
                val pageCount = JSONObject(statusResp.body?.string() ?: "{}").optJSONObject("args")?.optInt("page_count", 1) ?: 1

                log("INFO", "刷文档: $nodeName (${pageCount}页)")
                submitStudy(courseInfoId, node.optString("parentId"), pageCount, nodeId, pageCount)
                log("INFO", "文档完成: $nodeName")
            }
            "zip" -> {
                log("INFO", "刷资源: $nodeName")
                submitStudy(courseInfoId, node.optString("parentId"), 1, nodeId, 1)
                log("INFO", "资源完成: $nodeName")
            }
            "测验" -> {} // 跳过测验节点
        }
    }

    /** PUT /prod-api/teacher/studyRecord，body AES-ECB 加密 */
    private suspend fun submitStudy(courseInfoId: String, parentId: String, studyTime: Int, sourceId: String, totalNum: Int) = withContext(Dispatchers.IO) {
        val params = """{"courseInfoId":"$courseInfoId","id":"","parentId":"$parentId","studyTime":"$studyTime","sourceId":"$sourceId","studentId":"$userId","actualNum":"$totalNum","lastNum":"$totalNum","totalNum":"$totalNum"}"""
        val encrypted = aesEcbEncryptBase64(params)
        client.newCall(Request.Builder()
            .url("https://zyk.icve.com.cn/prod-api/teacher/studyRecord")
            .put(encrypted.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $zykAccessToken").build()).execute()
    }

    private fun aesEcbEncryptBase64(data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        return android.util.Base64.encodeToString(cipher.doFinal(data.toByteArray()), android.util.Base64.NO_WRAP)
    }

    /** "HH:MM:SS.nnn" 或 "HH:MM:SS" → 秒 */
    private fun parseDuration(s: String): Int {
        val parts = s.substringBefore(".").split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }
}
