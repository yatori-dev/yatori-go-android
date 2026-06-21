package com.yatori.android.network.platform.xuexitong

import retrofit2.http.*

/** 学习通平台 API */
interface XuexitongApi {

    @FormUrlEncoded
    @POST("https://passport2.chaoxing.com/fanyalogin")
    suspend fun login(
        @Field("fid") fid: String = "-1",
        @Field("uname") uname: String,
        @Field("password") password: String,
        @Field("t") t: Boolean = true,
        @Field("refer") refer: String = "https://i.chaoxing.com"
    ): XuexitongLoginResp

    @GET("https://mooc1-api.chaoxing.com/mycourse/backclazzdata")
    suspend fun courseList(
        @Query("view") view: String = "json",
        @Query("rss") rss: Int = 1
    ): XuexitongCoursesResp

    @GET("https://mooc1-api.chaoxing.com/gas/clazz")
    suspend fun taskPoints(
        @Query("id") clazzId: String,
        @Query("courseId") courseId: String,
        @Query("knowledgeid") knowledgeId: String,
        @Query("num") num: Int = 50,
        @Query("page") page: Int = 1
    ): XuexitongTaskResp
}

data class XuexitongLoginResp(val status: Boolean = false, val mes: String = "")
data class XuexitongCoursesResp(val result: Int = 0, val channelList: List<XuexitongChannel> = emptyList())
data class XuexitongChannel(val content: XuexitongContent? = null)
data class XuexitongContent(val course: XuexitongCourseWrapper? = null, val id: String = "")
data class XuexitongCourseWrapper(val data: List<XuexitongCourse> = emptyList())
data class XuexitongCourse(val id: String = "", val name: String = "", val teacherfactor: String = "")
data class XuexitongTaskResp(val data: List<XuexitongTask> = emptyList())
data class XuexitongTask(val id: String = "", val name: String = "", val type: Int = 0)
