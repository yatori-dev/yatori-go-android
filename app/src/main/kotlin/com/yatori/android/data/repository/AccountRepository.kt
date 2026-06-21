package com.yatori.android.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yatori.android.data.local.dao.AccountDao
import com.yatori.android.data.local.entity.AccountEntity
import com.yatori.android.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(private val dao: AccountDao) {
    private val gson = Gson()

    val accounts: Flow<List<Account>> = dao.observeAll().map { it.map(::toModel) }

    suspend fun save(account: Account): Long = dao.insert(toEntity(account))
    suspend fun update(account: Account) = dao.update(toEntity(account))
    suspend fun delete(account: Account) = dao.delete(toEntity(account))
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    private fun toModel(e: AccountEntity) = Account(
        id = e.id, platform = Platform.fromCode(e.platform),
        url = e.url, remarkName = e.remarkName, account = e.account, password = e.password,
        isProxy = e.isProxy,
        informEmails = gson.fromJson(e.informEmails, object : TypeToken<List<String>>() {}.type),
        coursesCustom = CoursesCustom(
            studyTime = e.studyTime, cxNode = e.cxNode,
            cxChapterTestSw = e.cxChapterTestSw, cxWorkSw = e.cxWorkSw, cxExamSw = e.cxExamSw,
            shuffleSw = e.shuffleSw, videoMode = VideoMode.fromValue(e.videoMode),
            autoExam = ExamMode.fromValue(e.autoExam), examAutoSubmit = e.examAutoSubmit,
            includeCourses = gson.fromJson(e.includeCourses, object : TypeToken<List<String>>() {}.type),
            excludeCourses = gson.fromJson(e.excludeCourses, object : TypeToken<List<String>>() {}.type)
        ),
        enabled = e.enabled
    )

    private fun toEntity(a: Account) = AccountEntity(
        id = a.id, platform = a.platform.code, url = a.url, remarkName = a.remarkName,
        account = a.account, password = a.password, isProxy = a.isProxy,
        informEmails = gson.toJson(a.informEmails),
        studyTime = a.coursesCustom.studyTime, cxNode = a.coursesCustom.cxNode,
        cxChapterTestSw = a.coursesCustom.cxChapterTestSw, cxWorkSw = a.coursesCustom.cxWorkSw,
        cxExamSw = a.coursesCustom.cxExamSw, shuffleSw = a.coursesCustom.shuffleSw,
        videoMode = a.coursesCustom.videoMode.value, autoExam = a.coursesCustom.autoExam.value,
        examAutoSubmit = a.coursesCustom.examAutoSubmit,
        includeCourses = gson.toJson(a.coursesCustom.includeCourses),
        excludeCourses = gson.toJson(a.coursesCustom.excludeCourses),
        enabled = a.enabled
    )
}
