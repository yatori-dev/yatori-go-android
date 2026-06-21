package com.yatori.android.engine

import com.yatori.android.domain.model.Account
import com.yatori.android.domain.model.AppSettings
import kotlinx.coroutines.CoroutineScope

/** 平台刷课运行器接口 */
interface PlatformBrushRunner {
    suspend fun run(
        taskId: String,
        account: Account,
        settings: AppSettings,
        scope: CoroutineScope,
        onProgress: (TaskProgress) -> Unit,
        log: suspend (level: String, msg: String) -> Unit = { _, _ -> }
    )
}
