package com.yatori.android.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yatori.android.data.datastore.SettingsDataStore
import com.yatori.android.data.repository.AccountRepository
import com.yatori.android.domain.model.Account
import com.yatori.android.domain.model.AppSettings
import com.yatori.android.engine.BrushEngine
import com.yatori.android.engine.BrushState
import com.yatori.android.engine.TaskProgress
import com.yatori.android.service.BrushService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val accountRepo: AccountRepository,
    private val settingsStore: SettingsDataStore,
    private val brushEngine: BrushEngine,
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountRepo.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val activeTasks: StateFlow<Map<String, TaskProgress>> = brushEngine.activeTasks

    val isRunning: StateFlow<Boolean> = activeTasks
        .map { it.values.any { p -> p.state == BrushState.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startAll() {
        ctx.startForegroundService(Intent(ctx, BrushService::class.java))
        brushEngine.startAll(accounts.value, settings.value)
    }

    fun startOne(account: Account) {
        ctx.startForegroundService(Intent(ctx, BrushService::class.java))
        brushEngine.start(account, settings.value)
    }

    fun isRunning(account: Account) = brushEngine.isRunning(account)

    fun cancelAll() = brushEngine.cancelAll()
    fun cancel(taskId: String) = brushEngine.cancel(taskId)
}
