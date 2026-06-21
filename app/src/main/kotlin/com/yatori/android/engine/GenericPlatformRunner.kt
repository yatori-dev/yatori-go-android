package com.yatori.android.engine

import com.yatori.android.domain.model.Account
import com.yatori.android.domain.model.AppSettings
import com.yatori.android.domain.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用平台占位 Runner（ENAEA/CQIE/KETANGX/WELEARN/ICVE/QSXT/HQKJ）
 * 各平台HTTP接口结构相似，后续可按平台特化实现
 */
@Singleton
class GenericPlatformRunner @Inject constructor() : PlatformBrushRunner {
    override suspend fun run(
        taskId: String,
        account: Account,
        settings: AppSettings,
        scope: CoroutineScope,
        onProgress: (TaskProgress) -> Unit,
        log: suspend (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress(TaskProgress(taskId, account, BrushState.RUNNING))
        // 各平台逻辑结构一致：登录 → 拉课程列表 → 遍历提交学时
        // 具体 API endpoint 因平台而异，在 DI 中可注入不同实现
        throw NotImplementedError("${account.platform.displayName} 平台暂未实现完整 API 对接，请关注后续版本更新")
    }
}
