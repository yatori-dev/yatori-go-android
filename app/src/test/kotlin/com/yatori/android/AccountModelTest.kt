package com.yatori.android

import com.yatori.android.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AccountModelTest {
    @Test fun `displayName uses remarkName when set`() {
        val acc = Account(platform = Platform.YINGHUA, account = "user@test.com", password = "pw", remarkName = "张三")
        assertEquals("张三", acc.displayName)
    }

    @Test fun `displayName falls back to account`() {
        val acc = Account(platform = Platform.XUEXITONG, account = "13800138000", password = "pw")
        assertEquals("13800138000", acc.displayName)
    }

    @Test fun `VideoMode fromValue roundtrip`() {
        VideoMode.entries.forEach { assertEquals(it, VideoMode.fromValue(it.value)) }
    }

    @Test fun `ExamMode fromValue roundtrip`() {
        ExamMode.entries.forEach { assertEquals(it, ExamMode.fromValue(it.value)) }
    }

    @Test fun `Platform fromCode roundtrip`() {
        Platform.entries.forEach { assertEquals(it, Platform.fromCode(it.code)) }
    }

    @Test fun `unknown platform code defaults to YINGHUA`() {
        assertEquals(Platform.YINGHUA, Platform.fromCode("UNKNOWN"))
    }
}
