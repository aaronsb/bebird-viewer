// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.bockelie.bebird.connection.GraceKeeper
import com.bockelie.bebird.connection.GraceService
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.settings.PrefsKeyValue
import com.bockelie.bebird.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the one [ScopeConnection] for the app's lifetime, so the screen and [GraceService] share
 * it: the connection outlives the Activity while it is kept in the background (#18).
 */
class BebirdApp : Application() {
    // The main thread, as ScopeConnection needs; never cancelled, like the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings by lazy { Settings(PrefsKeyValue(this)) }
    val connection by lazy { ScopeConnection(this, scope, settings) }
    val grace by lazy {
        GraceKeeper(
            connection, settings, SystemClock::elapsedRealtime,
            after = { ms, action -> scope.launch { delay(ms); action() } },
            startService = ::startGraceService,
            stayAwake = ::stayAwake,
        )
    }

    /** A partial wake lock: the CPU keeps running with the screen off, for at most [timeoutMs]. */
    private fun stayAwake(timeoutMs: Long): AutoCloseable {
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bebird:grace")
        lock.setReferenceCounted(false)
        lock.acquire(timeoutMs)
        return AutoCloseable { if (lock.isHeld) lock.release() }
    }

    private fun startGraceService(): Boolean =
        try {
            ContextCompat.startForegroundService(this, Intent(this, GraceService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: too long after leaving the screen
            Log.w("BebirdSpike", "can't start the grace service", e)
            false
        }
}
