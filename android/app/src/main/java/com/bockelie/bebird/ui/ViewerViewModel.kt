// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.band.BandFonts
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.control.RollFilter
import com.bockelie.bebird.settings.PrefsKeyValue
import com.bockelie.bebird.settings.Settings
import com.bockelie.bebird.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _band = MutableStateFlow(settings.band)
    val band: StateFlow<Boolean> = _band.asStateFlow()
    private val _circle = MutableStateFlow(settings.circle)
    val circle: StateFlow<Boolean> = _circle.asStateFlow()
    private val _label = MutableStateFlow(settings.label)
    val label: StateFlow<String> = _label.asStateFlow()

    // The band's fonts take a moment to parse; the band appears once they have.
    private val _bandRenderer = MutableStateFlow<BandRenderer?>(null)
    val bandRenderer: StateFlow<BandRenderer?> = _bandRenderer.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _bandRenderer.value = BandRenderer(BandFonts.load(app.assets))
            } catch (e: Exception) {
                Log.e("BebirdSpike", "band fonts failed to load", e)
            }
        }
    }

    fun setBand(on: Boolean) {
        settings.band = on
        _band.value = on
    }

    fun setCircle(on: Boolean) {
        settings.circle = on
        _circle.value = on
    }

    /** A blank label clears it. */
    fun setLabel(text: String) {
        settings.label = text
        _label.value = settings.label
    }

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
