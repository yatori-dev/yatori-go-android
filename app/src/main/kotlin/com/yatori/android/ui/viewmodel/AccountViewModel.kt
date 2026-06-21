package com.yatori.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yatori.android.data.repository.AccountRepository
import com.yatori.android.domain.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(private val repo: AccountRepository) : ViewModel() {

    val accounts: StateFlow<List<Account>> = repo.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(account: Account) = viewModelScope.launch { repo.save(account) }
    fun update(account: Account) = viewModelScope.launch { repo.update(account) }
    fun delete(account: Account) = viewModelScope.launch { repo.delete(account) }
    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(id, enabled) }
}
