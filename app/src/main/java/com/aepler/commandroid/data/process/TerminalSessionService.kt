// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aepler.commandroid.ui.CommandroidActivity
import com.termux.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bound + started foreground service that keeps the app process prioritised while Commandroid
 * shells run. Shells themselves live in the singleton [ProcessSessionRepository], so an activity
 * re-binding with `BIND_AUTO_CREATE` after a configuration change reattaches to the same jobs.
 * The service stops itself once it is unbound and no session has a command in flight.
 */
@AndroidEntryPoint
class TerminalSessionService : Service() {
    inner class LocalBinder : Binder() {
        val service: TerminalSessionService get() = this@TerminalSessionService
    }

    @Inject lateinit var repository: ProcessSessionRepository

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var boundClients = 0
    private var isForeground = false
    private var idleStopJob: Job? = null
    private var lastRunningCount = 0

    /**
     * True once onStartCommand ran. A service that only exists through bindService() is
     * destroyed as soon as its last client unbinds, whatever this class decides, so running
     * commands require the started state.
     */
    private var startRequested = false

    val sessions: ProcessSessionRepository get() = repository

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        scope.launch {
            repository.runningSessions.collect { running ->
                lastRunningCount = running.size
                if (running.isNotEmpty()) ensureStarted()
                if (isForeground) updateNotification()
                scheduleIdleCheck()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRequested = true
        if (intent?.action == ACTION_STOP) {
            // Promote first: a startForegroundService() call must always be answered.
            promoteToForeground()
            scope.launch {
                repository.closeAllSessions()
                stopNow()
            }
            return START_NOT_STICKY
        }
        promoteToForeground()
        scheduleIdleCheck()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        idleStopJob?.cancel()
        promoteToForeground()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        boundClients++
        idleStopJob?.cancel()
        promoteToForeground()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = 0
        scheduleIdleCheck()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun scheduleIdleCheck() {
        idleStopJob?.cancel()
        if (boundClients > 0 || lastRunningCount > 0) return
        idleStopJob = scope.launch {
            delay(IDLE_GRACE_MILLIS)
            if (boundClients == 0 && repository.runningSessions.value.isEmpty()) stopNow()
        }
    }

    /** Moves a bound-only instance (recreated by BIND_AUTO_CREATE) into the started state. */
    private fun ensureStarted() {
        if (startRequested) return
        // May be refused while the app is in the background; the activity also starts it in onStart.
        runCatching { startService(Intent(this, TerminalSessionService::class.java)) }
    }

    private fun stopNow() {
        startRequested = false
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun promoteToForeground() {
        if (isForeground) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        isForeground = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: RuntimeException) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background:
            // keep working as a plain bound/started service.
            false
        }
    }

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // Foreground status still holds; the notification is just hidden.
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Permission revoked concurrently.
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.commandroid_session_channel_name))
            .setDescription(getString(R.string.commandroid_session_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val text = if (lastRunningCount > 0) {
            resources.getQuantityString(R.plurals.commandroid_session_notification_running, lastRunningCount, lastRunningCount)
        } else {
            getString(R.string.commandroid_session_notification_idle)
        }
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, TerminalSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(getString(R.string.commandroid_session_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.commandroid_session_notification_stop), stopIntent)
        // Explicit: the package launch intent resolves to the legacy TermuxActivity, not the
        // Commandroid screen that owns these shells.
        val open = Intent(this, CommandroidActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        builder.setContentIntent(
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "commandroid_sessions"
        const val ACTION_STOP = "com.aepler.commandroid.action.STOP_SESSIONS"
        private const val NOTIFICATION_ID = 0x434d444c // "CMDL"
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val IDLE_GRACE_MILLIS = 5_000L

        /** Starts (and promotes to foreground) the service; call while the app is visible. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TerminalSessionService::class.java))
        }

        /** Intent for `bindService(intent, connection, Context.BIND_AUTO_CREATE)`. */
        fun bindIntent(context: Context): Intent = Intent(context, TerminalSessionService::class.java)

        /** Intent that closes every session and stops the service. */
        fun stopIntent(context: Context): Intent =
            Intent(context, TerminalSessionService::class.java).setAction(ACTION_STOP)
    }
}
