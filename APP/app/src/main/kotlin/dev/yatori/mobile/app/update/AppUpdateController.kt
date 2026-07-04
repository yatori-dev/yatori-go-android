package dev.yatori.mobile.app.update

import dev.yatori.mobile.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AppUpdateState(
    val checking: Boolean = false,
    val latestRelease: ReleaseInfo? = null,
)

class AppUpdateController(
    private val checker: AppUpdateChecker = AppUpdateChecker(),
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state
    private var startupChecked = false

    suspend fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        when (val result = checker.checkForUpdate(currentVersion)) {
            is UpdateResult.NewVersion -> _state.update { it.copy(latestRelease = result.release) }
            else -> Unit
        }
    }

    suspend fun checkManually(): UpdateResult {
        _state.update { it.copy(checking = true) }
        return try {
            when (val result = checker.checkForUpdate(currentVersion)) {
                is UpdateResult.NewVersion -> {
                    _state.update { it.copy(checking = false, latestRelease = result.release) }
                    result
                }
                is UpdateResult.UpToDate -> {
                    _state.update { it.copy(checking = false) }
                    result
                }
                is UpdateResult.Error -> {
                    _state.update { it.copy(checking = false) }
                    result
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(checking = false) }
            UpdateResult.Error(e.message ?: "检查更新失败")
        }
    }
}
