// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.connection.ScopeConnection

/**
 * Holds the [ScopeConnection] for the screen. Clearing the ViewModel disconnects; on
 * viewModelScope, so it also cancels a connect that is still waiting for a release.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    val connection = ScopeConnection(app, viewModelScope)

    override fun onCleared() {
        Log.i("BebirdSpike", "ViewModel cleared")
        connection.disconnect()
    }
}
