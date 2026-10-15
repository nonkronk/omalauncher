package dev.omalauncher

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.io.File
import dev.omalauncher.linux.LinuxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process (and with it proot and the Wayland server) in the
 * foreground while Linux is installed or installing; never without Linux.
 * The notification shows the install's progress.
 */
class LinuxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var syncthing: SyncthingSupervisor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux session", NotificationManager.IMPORTANCE_LOW))
        val state = (application as App).runtime.bootstrap.state
        startForeground(1, notification(state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (syncthing == null) {
            // Run under the existing Linux foreground service, not a terminal
            // and not an adb-launched PRoot; works across APK native-lib moves.
            syncthing = SyncthingSupervisor(
                (application as App).runtime.proot,
                scope,
                guestReady = { state.value == LinuxState.Installed },
            ).also { it.start() }
        }
        if (intent?.action == ACTION_RECONNECT_SYNC) syncthing?.reconnect()
        if (watch == null) {
            watch =
                scope.launch {
                    state.map(::text).distinctUntilChanged().collect { nm.notify(1, build(it)) }
                }
        }
        // Not restarted after the process dies: Linux only comes back with the launcher (Runtime.start),
        // and Android 12+ may refuse startForeground() in a restart from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: LinuxState) = build(text(state))

    private fun text(state: LinuxState): String =
        when (state) {
            is LinuxState.Installing -> "Installing Linux" + (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") + " · ${state.stage.label}"
            is LinuxState.Failed -> "Linux setup stopped · open settings › linux to retry"
            LinuxState.Removing -> "Removing Linux"
            else -> "Linux session running"
        }

    private fun build(text: String): Notification {
        val builder =
            Notification
                .Builder(this, CHANNEL)
                .setContentTitle(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        // The phone handles recovery locally; Tailscale stays the sync
        // transport and the loopback-only Syncthing GUI is not published.
        val config = File(filesDir, "arch/root/.local/state/syncthing/config.xml")
        if (config.isFile) {
            val intent = Intent(this, LinuxService::class.java).setAction(ACTION_RECONNECT_SYNC)
            val action = PendingIntent.getService(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification, "Reconnect Sync", action,
                ).build(),
            )
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val ACTION_RECONNECT_SYNC = "dev.omalauncher.RECONNECT_SYNC"
    }
}
