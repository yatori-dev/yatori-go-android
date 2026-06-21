package com.yatori.android.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yatori.android.data.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: SettingsDataStore
) : ViewModel() {
    fun markOnboarded() = viewModelScope.launch { store.setOnboarded() }
}
