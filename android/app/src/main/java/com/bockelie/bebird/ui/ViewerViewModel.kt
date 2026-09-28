// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.control.RollFilter
import com.bockelie.bebird.settings.PrefsKeyValue
import com.bockelie.bebird.settings.Settings
import com.bockelie.bebird.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the [ScopeConnection] and the view settings for the screen. Clearing the ViewModel
 * disconnects; on viewModelScope, so it also cancels a connect that is still waiting for a release.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(PrefsKeyValue(app))
    val connection = ScopeConnection(app, viewModelScope, settings)

    private val _autoRotate = MutableStateFlow(settings.autoRotate)
    val autoRotate: StateFlow<Boolean> = _autoRotate.asStateFlow()
    private val _trim = MutableStateFlow(settings.trim)
    val trim: StateFlow<Int> = _trim.asStateFlow()
    private val _theme = MutableStateFlow(settings.theme)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setAutoRotate(on: Boolean) {
        settings.autoRotate = on
        _autoRotate.value = on
    }

    /** One trim step (±15°), as the desktop's [ and ] keys. */
    fun stepTrim(steps: Int) {
        val t = RollFilter.stepTrim(_trim.value, steps)
        settings.trim = t
        _trim.value = t
    }

    fun setTheme(mode: ThemeMode) {
        settings.theme = mode
        _theme.value = mode
    }

    override fun onCleared() {
        Log.i("BebirdSpike", "ViewModel cleared")
        connection.disconnect()
    }
}
