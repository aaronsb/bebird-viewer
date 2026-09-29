// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.settings.KeyValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The proximity settings as the UI sees them, kept in step with [ProximitySettings] (persisted)
 * and the [gate] (whether the estimator runs). The sub-settings keep their values while the
 * master switch is off; they are only greyed out.
 */
class ProximityOptions(private val settings: ProximitySettings, private val gate: ProximityGate) {
    data class State(val enabled: Boolean, val style: ScaleStyle, val close: Boolean) {
        /** Scale and CLOSE can only be changed (and only matter) while estimation is on. */
        val subSettingsEnabled: Boolean get() = enabled
    }

    private val _state = MutableStateFlow(State(settings.enabled, settings.scaleStyle, settings.closeIndicator))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        gate.setEnabled(settings.enabled)
    }

    fun setEnabled(on: Boolean) {
        settings.enabled = on
        gate.setEnabled(on)
        _state.value = _state.value.copy(enabled = on)
    }

    fun setStyle(style: ScaleStyle) {
        settings.scaleStyle = style
        _state.value = _state.value.copy(style = style)
    }

    fun setClose(on: Boolean) {
        settings.closeIndicator = on
        _state.value = _state.value.copy(close = on)
    }
}

/**
 * The proximity settings, persisted in the app's settings store next to the others, each under
 * its own key: the master switch (on by default), the scale style (ring) and the CLOSE
 * indicator (on). Turning estimation off and on again keeps the other two.
 */
class ProximitySettings(private val kv: KeyValue) {
    var enabled: Boolean
        get() = kv.getBoolean("proximity", true)
        set(v) = kv.putBoolean("proximity", v)
    var scaleStyle: ScaleStyle
        get() = ScaleStyle.entries.firstOrNull { it.name == kv.getString("proximity_scale", "") } ?: ScaleStyle.RING
        set(v) = kv.putString("proximity_scale", v.name)
    var closeIndicator: Boolean
        get() = kv.getBoolean("proximity_close", true)
        set(v) = kv.putBoolean("proximity_close", v)
}
