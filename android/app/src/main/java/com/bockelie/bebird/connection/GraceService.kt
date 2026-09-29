// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.bockelie.bebird.BebirdApp
import com.bockelie.bebird.MainActivity
import com.bockelie.bebird.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app running while [GraceKeeper] keeps the connection in the background (#18): a
 * foreground service of type connectedDevice, with a notification counting down to the end of
 * the grace period and offering Disconnect and Power off. The connection itself stays with
 * [BebirdApp]; this only follows [GraceKeeper.kept] and stops once it is null.
 */
class GraceService : Service() {
    private val scope = MainScope()
    private var following: Job? = null
    private var lastStartId = 0
    private val keeper get() = (application as BebirdApp).grace
    private val connection get() = (application as BebirdApp).connection

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_DISCONNECT -> keeper.disconnectNow()
            ACTION_POWER_OFF -> keeper.powerOffNow()
            // startForegroundService() must be answered with startForeground(), even if the app
            // came back in the meantime and there is nothing left to keep.
            else -> try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification(keeper.kept.value, connection.isStreaming.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } catch (e: Exception) {
                // Not stopSelf(): stopping a service that was started for the foreground but never
                // got there can crash the app. Nothing more happens here, so Android lets it go.
                Log.e(TAG, "can't run in the foreground; ending the connection now", e)
                keeper.serviceFailed()
                return START_NOT_STICKY
            }
        }
        if (following == null) {
            following = scope.launch {
                keeper.kept.combine(connection.isStreaming, ::Pair).collect { (kept, streaming) ->
                    if (kept == null) {
                        ServiceCompat.stopForeground(this@GraceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // Not if the app left again since: that start is still to be answered.
                        stopSelf(lastStartId)
                    } else {
                        update(kept, streaming)
                    }
                }
            }
        }
        // If Android kills the process, the connection is gone with it: nothing to restart.
        return START_NOT_STICKY
    }

    /** Swiped away from the recent apps: closing the app ends the connection now. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        keeper.onClose()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("MissingPermission")  // without POST_NOTIFICATIONS the update is simply not shown
    private fun update(kept: GraceKeeper.Kept, streaming: Boolean) {
        NotificationManagerCompat.from(this).takeIf { it.areNotificationsEnabled() }
            ?.notify(NOTIFICATION_ID, notification(kept, streaming))
    }

    /** Power off is offered only once video has started; before that it would only disconnect. */
    private fun notification(kept: GraceKeeper.Kept?, streaming: Boolean): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.grace_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val device = connection.book.value.last?.label
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scope)
            .setContentTitle(device?.let { getString(R.string.grace_title_device, it) } ?: getString(R.string.grace_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open())
            .addAction(0, getString(R.string.disconnect), action(ACTION_DISCONNECT))
        if (streaming) b.addAction(0, getString(R.string.power_off), action(ACTION_POWER_OFF))
        when {
            kept == null -> {}
            kept.held -> b.setContentText(getString(R.string.grace_held))
            else -> {
                // The countdown, in the notification's time slot; Android ticks it, not us.
                val endsAtWall = System.currentTimeMillis() + (kept.endsAt - SystemClock.elapsedRealtime())
                b.setWhen(endsAtWall).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                b.setContentText(getString(if (kept.endsWithPowerOff && streaming) R.string.grace_then_power_off else R.string.grace_then_disconnect))
            }
        }
        return b.build()
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(what: String): PendingIntent = PendingIntent.getService(
        this, what.hashCode(), Intent(this, GraceService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "BebirdSpike"
        const val CHANNEL = "grace"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.bockelie.bebird.DISCONNECT"
        const val ACTION_POWER_OFF = "com.bockelie.bebird.POWER_OFF"
    }
}
