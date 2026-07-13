package com.material.xray.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AlwaysOnVpnState @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _active = MutableStateFlow(preferences.getBoolean(KEY_ACTIVE, false))
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun update(active: Boolean) {
        if (_active.value == active) return
        _active.value = active
        preferences.edit().putBoolean(KEY_ACTIVE, active).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "always_on_vpn"
        const val KEY_ACTIVE = "active"
    }
}
