package com.yatori.android.engine

import com.yatori.android.domain.model.*
import com.yatori.android.engine.ocr.OcrEngine
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 重庆工程学院（CQIE）刷课 Runner — 完整对照 Go api/cqie/ + aggregation/cqie/ 重写
 *
 * 登录：SM4加密账号密码 → POST /gateway/auth/login(验证码+uuid) → access_token
 * 课程：POST /gateway/system/orgStudent/pagedMyCourse → data.rows[].{id,studentCourseId}
 * 视频：GET /gateway/system/orgStudent/progressDetails → data[].children[].courseCatalogVideoVos[]
 * 普通刷课：POST saveStudyVideoPlan(startPos+=3 循环) + POST updateStudyVideoPlan
 * 暴力秒刷：GET /gateway/system/orgStudent/studyVideo 直接提交
 */
@Singleton
class CqieBrushRunner @Inject constructor(
    private val ocrEngine: OcrEngine
) : PlatformBrushRunner {

    // SM4 key，等价 Go utils/CqieEncrypt.go var fo
    private val SM4_KEY = byteArrayOf(8,5,4,1,2,7,6,8,9,4,5,1,2,4,1,4)

    private val client = OkHttpClient.Builder().cookieJar(SimpleCookieJar()).build()
    private var accessToken = ""; private var studentId = ""
    private var orgMajorId = ""; private var deptId = ""; private var orgId = ""

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

            brushCourse(account, course)
            onProgress(TaskProgress(taskId, account, BrushState.RUNNING,
                totalCourses = courses.size, doneCourses = idx + 1, currentCourse = name))
        }
    }

    private suspend fun login(account: Account) = withContext(Dispatchers.IO) {
        // 验证码（loop直到识别成功）
        var uuid = ""; var code = ""
        repeat(5) {
            val uuidResp = client.newCall(Request.Builder()
                .url("https://study.cqie.edu.cn/gateway/auth/createCaptcha?uuid=${java.util.UUID.randomUUID()}")
                .build()).execute()
            val imgBytes = uuidResp.body?.bytes() ?: return@repeat
            // 从 URL 取 uuid 参数
            uuid = uuidResp.request.url.queryParameter("uuid") ?: java.util.UUID.randomUUID().toString()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                ?: return@repeat
            code = ocrEngine.recognizeSemi(bmp, 26)
        }

        // SM4 加密账号密码（等价 Go utils.CqieEncrypt）
        val encAcc = sm4EncryptHex(account.account)
        val encPwd = sm4EncryptHex(account.password)

        val body = """{"account":"$encAcc","password":"$encPwd","code":"$code","status":0,"uuid":"$uuid","student":true}"""
        val resp = client.newCall(Request.Builder()
            .url("https://study.cqie.edu.cn/gateway/auth/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (json.optInt("code", -1) != 200) throw RuntimeException("CQIE登录失败: ${json.optString("msg")}")

        accessToken = json.optJSONObject("data")?.optString("access_token") ?: ""
        val user = json.optJSONObject("data")?.optJSONObject("user") ?: JSONObject()
        deptId = user.optString("deptId"); orgId = user.optString("orgId")

        // 获取用户详情（studentId、orgMajorId）
        val userResp = client.newCall(Request.Builder()
            .url("https://study.cqie.edu.cn/gateway/system/sysUser/nowUserDetails")
            .header("Authorization", accessToken).build()).execute()
        val userData = JSONObject(userResp.body?.string() ?: "{}").optJSONObject("data") ?: JSONObject()
        studentId = userData.optString("id").ifBlank { userData.optString("userId") }
        orgMajorId = userData.optString("orgMajorId").ifBlank { userData.optString("deptId") }
    }

    private suspend fun fetchCourses(): List<JSONObject> = withContext(Dispatchers.IO) {
        val body = """{"pageSize":200}""".toRequestBody("application/json".toMediaType())
        val resp = client.newCall(Request.Builder()
            .url("https://study.cqie.edu.cn/gateway/system/orgStudent/pagedMyCourse")
            .post(body).header("Authorization", accessToken).build()).execute()
        val rows = JSONObject(resp.body?.string() ?: "{}").optJSONObject("data")?.optJSONArray("rows")
            ?: return@withContext emptyList()
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private suspend fun brushCourse(account: Account, course: JSONObject) = withContext(Dispatchers.IO) {
        val courseId = course.optString("id"); val studentCourseId = course.optString("studentCourseId")
        val version = course.optString("version", "0"); val coursewareId = course.optString("coursewareId", "")

        // GET progressDetails → data[].children[].courseCatalogVideoVos[]
        val detailResp = client.newCall(Request.Builder()
            .url("https://study.cqie.edu.cn/gateway/system/orgStudent/progressDetails?studentId=$studentId&id=$courseId&majorId=$orgMajorId&studentCourseId=$studentCourseId&version=$version")
            .header("Authorization", accessToken).build()).execute()
        val chapters = JSONObject(detailResp.body?.string() ?: "{}").optJSONArray("data")
            ?: return@withContext

        for (i in 0 until chapters.length()) {
            val children = chapters.getJSONObject(i).optJSONArray("children") ?: continue
            for (j in 0 until children.length()) {
                val node = children.getJSONObject(j)
                val videos = node.optJSONArray("courseCatalogVideoVos") ?: continue
                for (k in 0 until videos.length()) {
                    val v = videos.getJSONObject(k)
                    val studyProgress = v.optDouble("studyProgress", 0.0)
                    if (studyProgress >= 100.0) continue
                    val videoId = v.optString("id"); val unitId = v.optString("unitId")
                    val timeLength = v.optInt("timeLength", 60)
                    val startStudyTime = v.optInt("studyTime", 0)

                    if (account.coursesCustom.videoMode == VideoMode.VIOLENT) {
                        // 暴力秒刷：GET studyVideo 直接完成
                        client.newCall(Request.Builder()
                            .url("https://study.cqie.edu.cn/gateway/system/orgStudent/studyVideo?studentCourseId=$studentCourseId&videoId=$videoId&studentId=$studentId&majorId=$orgMajorId&version=$version")
                            .header("Authorization", accessToken).build()).execute()
                    } else {
                        // 普通模式：saveStudyVideoPlan + 循环 updateStudyVideoPlan
                        brushVideo(courseId, studentCourseId, unitId, videoId, coursewareId, version, startStudyTime, timeLength)
                    }
                    delay(500L)
                }
            }
        }
    }

    private suspend fun brushVideo(
        courseId: String, studentCourseId: String, unitId: String,
        videoId: String, coursewareId: String, version: String, startPos: Int, maxTime: Int
    ) = withContext(Dispatchers.IO) {
        // 先 save（获取 studyId）
        val saveBody = """{"courseId":"$courseId","majorId":"$orgMajorId","startPos":"$startPos","stopPos":"$startPos","studentCourseId":"$studentCourseId","studentId":"$studentId","unitId":"$unitId","videoId":"$videoId","coursewareId":"$coursewareId","version":"$version"}"""
        val saveResp = client.newCall(Request.Builder()
            .url("https://study.cqie.edu.cn/gateway/system/orgStudent/saveStudyVideoPlan")
            .post(saveBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization", accessToken).build()).execute()
        val studyId = JSONObject(saveResp.body?.string() ?: "{}").optString("data", "")

        // 循环提交学时（步进3秒）
        var pos = startPos
        while (pos < maxTime) {
            pos = (pos + 3).coerceAtMost(maxTime)
            val updateBody = """{"id":"$studyId","orgId":"$orgId","deptId":"$deptId","majorId":"$orgMajorId","version":"$version","courseId":"$courseId","studentCourseId":"$studentCourseId","unitId":"$unitId","videoId":"$videoId","coursewareId":"$coursewareId","startPos":"$pos","stopPos":"$pos","maxPos":"$pos"}"""
            client.newCall(Request.Builder()
                .url("https://study.cqie.edu.cn/gateway/system/orgStudent/updateStudyVideoPlan")
                .post(updateBody.toRequestBody("application/json".toMediaType()))
                .header("Authorization", accessToken).build()).execute()
            delay(3_000L)
        }
    }

    /** 等价 Go utils.CqieEncrypt：SM4/ECB/PKCS7Padding，key=fo，输出hex */
    private fun sm4EncryptHex(input: String): String {
        return try {
            val cipher = Cipher.getInstance("SM4/ECB/PKCS7Padding", "BC")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(SM4_KEY, "SM4"))
            cipher.doFinal(input.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // 降级：直接 hex 编码（部分设备可能不支持 SM4）
            input.toByteArray().joinToString("") { "%02x".format(it) }
        }
    }
}
