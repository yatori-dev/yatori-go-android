package com.yatori.android.network.platform.yinghua

import retrofit2.http.*

/** 英华学堂平台 API（等价移植 Go yatori-go-core/api/yinghua） */
interface YinghuaApi {

    @FormUrlEncoded
    @POST("{preUrl}/v1/api/login")
    suspend fun login(
        @Path("preUrl", encoded = true) preUrl: String,
        @Field("username") username: String,
        @Field("password") password: String
    ): YinghuaResponse<YinghuaLoginData>

    @GET("{preUrl}/v1/api/course/list")
    suspend fun courseList(
        @Path("preUrl", encoded = true) preUrl: String,
        @Header("Authorization") token: String
    ): YinghuaResponse<List<YinghuaCourse>>

    @GET("{preUrl}/v1/api/course/{courseId}/nodes")
    suspend fun nodeList(
        @Path("preUrl", encoded = true) preUrl: String,
        @Path("courseId") courseId: String,
        @Header("Authorization") token: String
    ): YinghuaResponse<List<YinghuaNode>>

    @FormUrlEncoded
    @POST("{preUrl}/v1/api/study/submit")
    suspend fun submitStudyTime(
        @Path("preUrl", encoded = true) preUrl: String,
        @Header("Authorization") token: String,
        @Field("nodeId") nodeId: String,
        @Field("studyId") studyId: String,
        @Field("time") time: Int
    ): YinghuaResponse<YinghuaStudyResult>

    @GET("{preUrl}/v1/api/keepalive")
    suspend fun keepAlive(
        @Path("preUrl", encoded = true) preUrl: String,
        @Header("Authorization") token: String
    ): YinghuaResponse<Any>

    @GET("{preUrl}/v1/api/course/{courseId}/detail")
    suspend fun courseDetail(
        @Path("preUrl", encoded = true) preUrl: String,
        @Path("courseId") courseId: String,
        @Header("Authorization") token: String
    ): YinghuaResponse<YinghuaCourseDetail>

    @GET("{preUrl}/v1/api/node/{nodeId}/work")
    suspend fun workDetail(
        @Path("preUrl", encoded = true) preUrl: String,
        @Path("nodeId") nodeId: String,
        @Header("Authorization") token: String
    ): YinghuaResponse<List<YinghuaWork>>

    @GET("{preUrl}/v1/api/node/{nodeId}/exam")
    suspend fun examDetail(
        @Path("preUrl", encoded = true) preUrl: String,
        @Path("nodeId") nodeId: String,
        @Header("Authorization") token: String
    ): YinghuaResponse<List<YinghuaExam>>
}

data class YinghuaResponse<T>(
    val status: Boolean = false,
    val msg: String = "",
    val result: T? = null,
    val _code: Int = 0
)
data class YinghuaLoginData(val token: String = "", val userId: String = "")
data class YinghuaCourse(
    val id: String = "",
    val name: String = "",
    val startDate: String = "",
    val endDate: String = ""
)
data class YinghuaNode(
    val id: String = "",
    val name: String = "",
    val tabVideo: Boolean = false,
    val tabWork: Boolean = false,
    val tabExam: Boolean = false,
    val progress: Float = 0f,
    val videoDuration: Int = 0,
    val viewedDuration: Int = 0,
    val errorMessage: String = ""
)
data class YinghuaStudyResult(val studyId: Long = 0, val data: YinghuaStudyData? = null)
data class YinghuaStudyData(val studyId: Long = 0)
data class YinghuaCourseDetail(val videoLearned: Int = 0, val videoCount: Int = 0, val progress: Float = 0f)
data class YinghuaWork(val id: String = "", val name: String = "", val score: Float = 0f)
data class YinghuaExam(val id: String = "", val name: String = "", val score: Float = 0f)
