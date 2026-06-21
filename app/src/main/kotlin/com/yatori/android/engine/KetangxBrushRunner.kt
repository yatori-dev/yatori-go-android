package com.yatori.android.engine

import com.yatori.android.domain.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 码上研训（KETANGX）刷课 Runner — 完整对照 Go api/ketangx/ + aggregation/ketangx/ 重写
 *
 * 登录：POST https://www.ketangx.cn/Login/AccLogin → userId/Id
 * 课程：POST Activity/Query (actType=2) → HTML解析 div.course-history[activityid]
 * 节点：GET DoAct/ActIndex/{activityId} → HTML解析 li.wis-leftNodeItem[sectli]
 * 提交：GET DoAct/GetSection?id={sectId} (标记) + POST Common/SetDuration (完成)
 */
@Singleton
class KetangxBrushRunner @Inject constructor() : PlatformBrushRunner {

    private val client = OkHttpClient.Builder().cookieJar(SimpleCookieJar()).build()
    private var userId = ""; private var id = ""

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
            val name = course.optString("title")
            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses
            if (exclude.isNotEmpty() && exclude.contains(name)) return@forEachIndexed
            if (include.isNotEmpty() && !include.contains(name)) return@forEachIndexed

            brushCourse(course.optString("activityId"))
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name))
        }
    }

    /** 等价 Go LoginAction：POST AccLogin → userId/Id，再 GET MyInfo */
    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        val body = "username=${account.account}&password=${account.password}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val resp = client.newCall(Request.Builder()
            .url("https://www.ketangx.cn/Login/AccLogin").post(body).build()).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (json.optBoolean("Success") != true) throw RuntimeException("码上研训登录失败: $json")

        // GET MyInfo 获取 UserId / Id（等价 Go PullPersonInfoApi）
        val info = client.newCall(Request.Builder()
            .url("https://www.ketangx.cn/Comment/MyInfo?topicType=2").build()).execute()
        val infoJson = JSONObject(info.body?.string() ?: "{}")
        userId = infoJson.optString("UserId")
        id = infoJson.optString("Id")
    }

    /**
     * 等价 Go PullCourseListAction：POST Activity/Query(actType=2) → HTML
     * 解析 div.course-history[activityid][title] + .progress-label .num
     */
    private suspend fun fetchCourses(): List<JSONObject> = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val body = "actType=2&actStart=&actClose=&formId=&classId=&actKey=&actState=&timeId=$ts"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val html = client.newCall(Request.Builder()
            .url("https://www.ketangx.cn/Activity/Query").post(body).build())
            .execute().body?.string() ?: return@withContext emptyList()

        val doc = Jsoup.parse(html)
        doc.select("div.course-history[activityid]").mapNotNull { el ->
            JSONObject().apply {
                put("activityId", el.attr("activityid"))
                put("title", el.attr("title").ifBlank { el.text() })
                put("progress", el.select(".progress-label .num").text().toDoubleOrNull() ?: 0.0)
            }
        }
    }

    /**
     * 等价 Go PullNodeListAction：GET DoAct/ActIndex/{activityId} → HTML
     * 解析 li.wis-leftNodeItem[sectli] → sectId/title/isComplete/type
     * 再逐节点 CompleteVideoAction：GET GetSection + POST SetDuration
     */
    private suspend fun brushCourse(activityId: String) = withContext(Dispatchers.IO) {
        val html = client.newCall(Request.Builder()
            .url("https://www.ketangx.cn/DoAct/ActIndex/$activityId?_=${System.currentTimeMillis()}")
            .build()).execute().body?.string() ?: return@withContext

        val doc = Jsoup.parse(html)
        val nodes = doc.select("li.wis-leftNodeItem[sectli]")

        for (el in nodes) {
            val type = el.select("div.wis-iconActive-tit").text()
            if (type != "视频" && type != "文档") continue
            val isComplete = el.hasClass("complete") || el.select(".wis-complete").isNotEmpty()
            if (isComplete) continue
            val sectId = el.attr("sectli").ifBlank { el.attr("sectid") }
            if (sectId.isEmpty()) continue

            completeNode(sectId, activityId)
            delay(500L)
        }
    }

    /**
     * 等价 Go CompleteVideoAction：
     * 1. GET DoAct/GetSection?id={sectId} (SignVideoStatusApi)
     * 2. POST Common/SetDuration studyData[SectId]=&studyData[UserId]=&studyData[StudyTime]=114514&studyData[Duraion]=114514
     */
    private suspend fun completeNode(sectId: String, activityId: String) = withContext(Dispatchers.IO) {
        // Step 1: 标记节点（SignVideoStatusApi）
        client.newCall(Request.Builder()
            .url("https://www.ketangx.cn/DoAct/GetSection?id=$sectId&_=${System.currentTimeMillis()}")
            .build()).execute()

        // Step 2: 提交完成（CompleteVideoApi）— studyTime=114514 是 Go 原始值
        val form = "studyData%5BSectId%5D=$sectId&studyData%5BUserId%5D=$id&studyData%5BStudyTime%5D=114514&studyData%5BDuraion%5D=114514"
        val resp = client.newCall(Request.Builder()
            .url("https://www.ketangx.cn/Common/SetDuration")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("Referer", "https://www.ketangx.cn/DoAct/ActIndex/$activityId")
            .build()).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        // Success=false 说明节点已完成或无需处理
    }
}
