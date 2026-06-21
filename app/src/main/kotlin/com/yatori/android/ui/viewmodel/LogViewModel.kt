package com.yatori.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yatori.android.data.datastore.SettingsDataStore
import com.yatori.android.data.local.dao.LogDao
import com.yatori.android.data.local.entity.LogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logDao: LogDao,
    private val store: SettingsDataStore
) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    private val _accountFilter = MutableStateFlow<String?>(null)

    val keyword: StateFlow<String> = _keyword
    val accountFilter: StateFlow<String?> = _accountFilter

    /** 日志显示级别来自全局设置（在「设置」页配置），"ALL" 表示全部 */
    private val levelFilter: Flow<String?> =
        store.settings.map { it.logLevel.takeIf { lv -> lv != "ALL" } }.distinctUntilChanged()

    val accounts: StateFlow<List<String>> = logDao.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> = combine(levelFilter, _keyword, _accountFilter) { level, kw, acc ->
        logDao.observeFiltered(level, kw.ifBlank { null }, acc)
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKeyword(kw: String) { _keyword.value = kw }
    fun setAccount(acc: String?) { _accountFilter.value = acc }
    fun clear() = viewModelScope.launch { logDao.clear() }
}
