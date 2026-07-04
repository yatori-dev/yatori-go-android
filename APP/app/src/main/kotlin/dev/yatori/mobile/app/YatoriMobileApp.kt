package dev.yatori.mobile.app

import android.app.Application
import dev.yatori.mobile.app.security.AppIntegrityChecker
import kotlin.system.exitProcess

class YatoriMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!AppIntegrityChecker.check(this)) {
            exitProcess(0)
        }
    }
}
