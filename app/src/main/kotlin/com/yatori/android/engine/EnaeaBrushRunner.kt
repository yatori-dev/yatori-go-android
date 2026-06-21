package com.yatori.android.engine

import com.yatori.android.domain.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 学习公社（ENAEA）刷课 Runner — 完整对照 Go api/enaea/EnaeaApi.go 重写
 *
 * 登录：GET passport.enaea.edu.cn/login.do?j_username=&j_password=MD5 → ASUSS cookie
 * 项目：GET assessment.do?action=getMyCircleCourses → result.list[].circleId
 * 课程：GET circleIndexRedirect.do?action=toCircleIndex(跳转) →
 *       GET circleIndex.do?action=toNewMyClass&type=course → result.list[].{courseId,id}
 * 视频：GET course.do?action=getCourseContentList → result.list[].{id,tccId}
 * 开始学习：GET course.do?action=statisticForCCVideo → SCFUCKPKey/Value
 * 提交学时(普通)：POST studyLog.do id=&circleId=&ct={timestamp}&finish=false
 * 提交学时(暴力)：POST studyLog.do id=&circleId=&ct={timestamp}&finish=false&studyMins={min}
 */
@Singleton
class EnaeaBrushRunner @Inject constructor() : PlatformBrushRunner {

    private val client = OkHttpClient.Builder().cookieJar(SimpleCookieJar())
        .followRedirects(true).build()

    override suspend fun run(
        taskId: String, account: Account, settings: AppSettings,
        scope: CoroutineScope, onProgress: (TaskProgress) -> Unit,
 log: suspend (String, String) -> Unit
) = withContext(Dispatchers.IO) {
        login(account)
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING))

        val projects = fetchProjects()
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING, totalCourses = projects.size))

        projects.forEachIndexed { idx, project ->
            val circleId = project.opt("circleId")?.toString() ?: return@forEachIndexed
            brushCircle(account, circleId)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = projects.size, doneCourses = idx + 1))
        }
    }

    /** 等价 Go LoginApi：GET /login.do?j_password=MD5(password) */
    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val md5pw = md5(account.password)
        val url = "https://passport.enaea.edu.cn/login.do?ajax=true&jsonp=ablesky_$ts" +
            "&j_username=${account.account}&j_password=$md5pw&_acegi_security_remember_me=false&_=$ts"
        val resp = client.newCall(Request.Builder().url(url)
            .header("Referer", "https://study.enaea.edu.cn/login.do").build()).execute()
        val body = resp.body?.string() ?: ""
        if (body.contains("用户名或密码错误")) throw RuntimeException("学习公社登录失败：用户名或密码错误")
    }

    /** 等价 Go PullProjectsApi：GET assessment.do?action=getMyCircleCourses */
    private suspend fun fetchProjects(): List<JSONObject> = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val resp = client.newCall(Request.Builder()
            .url("https://study.enaea.edu.cn/assessment.do?action=getMyCircleCourses&start=0&limit=200&isFinished=false&_=$ts")
            .build()).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        val list = json.optJSONObject("result")?.optJSONArray("list") ?: return@withContext emptyList()
        (0 until list.length()).map { list.getJSONObject(it) }
    }

    private suspend fun brushCircle(account: Account, circleId: String) = withContext(Dispatchers.IO) {
        // GET circleIndexRedirect（跳转，自动跟随cookie）
        client.newCall(Request.Builder()
            .url("https://study.enaea.edu.cn/circleIndexRedirect.do?action=toCircleIndex&circleId=$circleId&ct=${System.currentTimeMillis()}")
            .build()).execute()

        // 拉课程列表：GET circleIndex.do?action=toNewMyClass&type=course
        val ts = System.currentTimeMillis()
        val coursesResp = client.newCall(Request.Builder()
            .url("https://study.enaea.edu.cn/circleIndex.do?action=toNewMyClass&start=0&limit=200&isCompleted=&circleId=$circleId&syllabusId=&categoryRemark=all&_=$ts")
            .build()).execute()
        val coursesJson = JSONObject(coursesResp.body?.string() ?: "{}")
        val courses = coursesJson.optJSONObject("result")?.optJSONArray("list") ?: return@withContext

        for (i in 0 until courses.length()) {
            val course = courses.getJSONObject(i)
            val courseId = course.optString("courseId").ifBlank { course.optString("id") }
            val courseName = course.optString("remark", course.optString("courseTitle"))

            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses
            if (exclude.isNotEmpty() && exclude.contains(courseName)) continue
            if (include.isNotEmpty() && !include.contains(courseName)) continue

            if (course.optString("coursecontentType") == "video") {
                brushCourseVideos(account, circleId, courseId, course)
            }
        }
    }

    private suspend fun brushCourseVideos(account: Account, circleId: String, courseId: String, courseInfo: JSONObject) = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val videoResp = client.newCall(Request.Builder()
            .url("https://study.enaea.edu.cn/course.do?action=getCourseContentList&courseId=$courseId&circleId=$circleId&_=$ts")
            .build()).execute()
        val videos = JSONObject(videoResp.body?.string() ?: "{}").optJSONObject("result")?.optJSONArray("list") ?: return@withContext

        val mode = account.coursesCustom.videoMode
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            val progress = v.optString("studyProgress", "0").toFloatOrNull() ?: 0f
            if (progress >= 100f) continue
            val videoId = v.opt("id")?.toString() ?: continue

            // 统计开始（获取 key/value），等价 Go StatisticTicForCCVideAction
            val statTs = System.currentTimeMillis()
            val statResp = client.newCall(Request.Builder()
                .url("https://study.enaea.edu.cn/course.do?action=statisticForCCVideo&courseId=$courseId&coursecontentId=$videoId&circleId=$circleId&_=$statTs")
                .build()).execute()
            val statJson = JSONObject(statResp.body?.string() ?: "{}")
            if (statJson.optBoolean("success") != true) continue
            val key = statJson.optString("key"); val value = statJson.optString("value")

            // 提交学时
            if (mode == VideoMode.VIOLENT) {
                // 暴力模式：附带 studyMins，length 是 "HH:MM:SS" 格式，需转换为分钟
                val lengthStr = v.optString("length", "00:30:00")
                val studyMins = parseLengthToMinutes(lengthStr).coerceAtLeast(1)
                submitStudyTime(videoId, circleId, key, value, studyMins = studyMins)
            } else {
                // 普通模式：提交当前时间戳
                repeat(3) {
                    submitStudyTime(videoId, circleId, key, value)
                    delay(5_000L)
                }
            }
        }
    }

    private suspend fun submitStudyTime(videoId: String, circleId: String, key: String, value: String, studyMins: Int = -1) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("id", videoId).add("circleId", circleId)
            .add(key, value)
            .add("ct", System.currentTimeMillis().toString())
            .add("finish", "false")
            .apply { if (studyMins > 0) add("studyMins", studyMins.toString()) }
            .build()
        client.newCall(Request.Builder()
            .url("https://study.enaea.edu.cn/studyLog.do").post(form)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()).execute()
    }

    private fun parseLengthToMinutes(s: String): Int {
        val parts = s.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1] + if (parts[2] > 0) 1 else 0
            2 -> parts[0] + if (parts[1] > 0) 1 else 0
            1 -> parts[0]
            else -> 30
        }
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(s.toByteArray())).toString(16).padStart(32, '0')
    }
}
