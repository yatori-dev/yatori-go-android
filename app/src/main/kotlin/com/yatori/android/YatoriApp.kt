package com.yatori.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yatori.android.security.AppIntegrityChecker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class YatoriApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // release 包签名/包名不匹配（二次打包）直接退出；debug 包自动跳过
        if (!AppIntegrityChecker.check(this)) {
            exitProcess(0)
        }
    }
}
