package com.yatori.android.engine

import com.yatori.android.domain.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * 随行课堂 WeLearn 刷课 Runner — 完整对照 Go api/welearn/ 重写
 *
 * 登录：POST sso.sflep.com/idsvr/account/login (密码XOR+Base64加密) → SSO回调
 * 课程：GET authCourse.aspx?action=gmc → clist[].{scid,cid,uid,classid}
 * 章节：GET StudyStat.aspx?action=courseunits → info[].{id,unitname,unitidx}
 * 任务点：GET StudyStat.aspx?action=scoLeaves&unitidx={index} → info[].{id,iscomplete}
 * 提交学时(普通)：POST SCO.aspx action=getscoinfo_v7
 * 提交完成(暴力)：POST SCO.aspx action=getscoinfo_v7 + cstatus=completed
 */
@Singleton
class WeLearnBrushRunner @Inject constructor() : PlatformBrushRunner {

    private val client = OkHttpClient.Builder()
        .cookieJar(SimpleCookieJar()).followRedirects(true).build()

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
            val name = course.optString("courseName", course.optString("cid"))
            val include = account.coursesCustom.includeCourses
            val exclude = account.coursesCustom.excludeCourses
            if (exclude.isNotEmpty() && exclude.contains(name)) return@forEachIndexed
            if (include.isNotEmpty() && !include.contains(name)) return@forEachIndexed

            brushCourse(account, course)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name))
        }
    }

    // ─── 登录（等价 Go GenerateCipherText + WeLearnLoginApi + WeLearnLoginSsoCallApi）───

    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        val (encPwd, ts) = generateCipherText(account.password)
        val payload = "rturl=%2Fconnect%2Fauthorize%2Fcallback%3Fclient_id%3Dwelearn_web%26redirect_uri%3D" +
            "https%253A%252F%252Fwelearn.sflep.com%252Fsignin-sflep%26response_type%3Dcode%26scope%3D" +
            "openid%2520profile%2520email%2520phone%2520address%26code_challenge%3D" +
            "p18_2UckWpdGfknVKQp6Ang64zAYH6__0Z8eQu2uuZE%26code_challenge_method%3DS256%26state%3D" +
            "OpenIdConnect.AuthenticationProperties%253DBhc1Qn6lYFZrxO_KhC7UzXZTYACtsAnIVT0PgzDlhtux" +
            "IXeSFLwXaNbthEeuwSCbzvhrw2wECCxFTq8tbd7k2OFPfH0_TCnMkuh8oBFmlhEsZ3ZXYecidfT2h2YpAyAoa" +
            "BaXfpuQj2SGCIEW3KVRYpnljmx-mso97xCbjz72URywiBJRMqDS9TqY-0vaviUIH1X72u_phfuiBdbR1s-WOy" +
            "Uj21KAPdNPJXi1nQtUd-hRoeI53WBTrv2EC0U4SNFvhivPgE6YseB2fdYbPv4u0NiFeHPD3EBQyqE_iUVI1Qr" +
            "GPG3VvhD5xs8odx21WncybewKIuTQpH3MAfJkTmDeQ%26x-client-SKU%3DID_NET472%26x-client-ver%3D6.32.1.0" +
            "&account=${account.account}&pwd=$encPwd&ts=$ts"

        val resp = client.newCall(
            Request.Builder().url("https://sso.sflep.com/idsvr/account/login")
                .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        ).execute()
        val body = resp.body?.string() ?: ""
        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        if (json.optString("msg").isNotBlank() && json.optString("msg") != "OK") {
            throw RuntimeException("WeLearn登录失败: ${json.optString("msg")}")
        }
        // SSO 回调
        client.newCall(Request.Builder().url("https://welearn.sflep.com/student/index.aspx").build()).execute()
    }

    /** 等价 Go GenerateCipherText：时间戳XOR + hex + base64 */
    private fun generateCipherText(password: String): Pair<String, Long> {
        val t0 = System.currentTimeMillis()
        var v = ((t0 shr 16) and 0xFF).toByte()
        password.toByteArray().forEach { v = (v.toInt() xor it.toInt()).toByte() }
        val remainder = (v.toInt().absoluteValue % 100).toLong()
        val t1 = (t0 / 100) * 100 + remainder
        val hex = password.toByteArray().joinToString("") { "%02x".format(it) }
        val s = "$t1*$hex"
        return Base64.getEncoder().encodeToString(s.toByteArray()) to t1
    }

    // ─── 课程列表（GET authCourse.aspx?action=gmc）────────────────────────────────

    private suspend fun fetchCourses(): List<JSONObject> = withContext(Dispatchers.IO) {
        val nc = Random.nextFloat()
        val resp = client.newCall(
            Request.Builder().url("https://welearn.sflep.com/ajax/authCourse.aspx?action=gmc&nocache=$nc").build()
        ).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (json.optInt("ret", -1) != 0) return@withContext emptyList()
        val clist = json.optJSONArray("clist") ?: return@withContext emptyList()
        (0 until clist.length()).map { clist.getJSONObject(it) }
    }

    // ─── 单课程刷取 ───────────────────────────────────────────────────────────────

    private suspend fun brushCourse(account: Account, course: JSONObject) = withContext(Dispatchers.IO) {
        val cid     = course.optString("cid")
        val uid     = course.optString("uid")    // 注意：WeLearn 的 uid 是课程维度的学生ID
        val classId = course.optString("classid")
        val scid    = course.optString("scid")

        // 拉章节列表
        val chapResp = client.newCall(Request.Builder()
            .url("https://welearn.sflep.com/ajax/StudyStat.aspx?action=courseunits&cid=$cid&stuid=$uid&classid=$classId&nocache=${Random.nextFloat()}")
            .build()).execute()
        val chapJson = JSONObject(chapResp.body?.string() ?: "{}")
        if (chapJson.optInt("ret", -1) != 0) return@withContext
        val chapters = chapJson.optJSONArray("info") ?: return@withContext

        for (unitIdx in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(unitIdx)
            if (!chapter.optBoolean("visible", true)) continue

            // 拉任务点列表（unitidx 是顺序索引，不是 id！）
            val ptResp = client.newCall(Request.Builder()
                .url("https://welearn.sflep.com/ajax/StudyStat.aspx?action=scoLeaves&cid=$cid&stuid=$uid&unitidx=$unitIdx&classid=$classId&nocache=${Random.nextFloat()}")
                .build()).execute()
            val ptJson = JSONObject(ptResp.body?.string() ?: "{}")
            if (ptJson.optInt("ret", -1) != 0) continue
            val points = ptJson.optJSONArray("info") ?: continue

            for (j in 0 until points.length()) {
                val pt = points.getJSONObject(j)
                if (pt.optString("iscomplete") == "已完成") continue
                if (!pt.optBoolean("isvisible", true)) continue
                val scoId = pt.optString("id")

                submitPoint(cid, uid, classId, scoId, account.coursesCustom.videoMode)
            }
        }
    }

    /**
     * 提交学时，等价 Go WeLearnSubmitStudyTimeAction
     * 普通：POST SCO.aspx action=getscoinfo_v7（获取当前状态，ret=0即成功）
     * 暴力：同上 + cstatus=completed
     */
    private suspend fun submitPoint(cid: String, uid: String, classId: String, scoId: String, mode: VideoMode) = withContext(Dispatchers.IO) {
        // Step 1: getscoinfo_v7 → 获取当前状态
        val nc1 = Random.nextFloat()
        val form1 = "action=getscoinfo_v7&cid=$cid&scoid=$scoId&uid=$uid&nocache=$nc1"
        val resp1 = postSco(uid, form1)
        val ret = resp1.optInt("ret", -1)

        if (ret == -1) return@withContext

        // Step 2: 开始学习 (StartStudyApi) — 触发记录
        val isComplete = mode == VideoMode.VIOLENT || mode == VideoMode.NORMAL
        val nc2 = Random.nextFloat()
        val form2 = buildString {
            append("action=initializationComplete&cid=$cid&scoid=$scoId&uid=$uid")
            append("&crate=100&classid=$classId")
            if (isComplete) append("&cstatus=completed&trycount=0")
            append("&nocache=$nc2")
        }
        val resp2 = postSco(uid, form2, cid, classId, scoId)
        if (resp2.optInt("ret", -1) == -1) {
            // 重试一次 getscoinfo_v7
            val nc3 = Random.nextFloat()
            postSco(uid, "action=getscoinfo_v7&cid=$cid&scoid=$scoId&uid=$uid&nocache=$nc3")
        }

        delay(1_000L)
    }

    private suspend fun postSco(uid: String, form: String, cid: String = "", classId: String = "", scoId: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val referer = if (cid.isNotEmpty())
            "https://welearn.sflep.com/Student/StudyCourse.aspx?cid=$cid&classid=$classId&sco=$scoId"
        else "https://welearn.sflep.com/Student/StudyCourse.aspx"

        val resp = client.newCall(
            Request.Builder()
                .url("https://welearn.sflep.com/Ajax/SCO.aspx?uid=$uid")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("Referer", referer)
                .build()
        ).execute()
        runCatching { JSONObject(resp.body?.string() ?: "{}") }.getOrDefault(JSONObject())
    }
}
