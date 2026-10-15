package dev.omalauncher

import android.util.Log
import dev.omalauncher.linux.Proot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Phone-owned Syncthing process. It lives in the existing Linux foreground
 * service instead of an adb-launched, short-lived PRoot shell.
 *
 * No extra network listener is exposed. The REST API is used on loopback and
 * replication keeps the already-configured Tailscale IPv4 peer address.
 */
internal class SyncthingSupervisor(
    private val proot: Proot,
    private val scope: CoroutineScope,
    private val guestReady: () -> Boolean,
) {
    private val lock = Mutex()
    private var watcher: Job? = null
    private var process: Process? = null
    private var misses = 0
    private var lastRestart = 0L

    private val binary get() = File(proot.rootfs, "usr/bin/syncthing")
    private val config get() = File(proot.home, ".local/state/syncthing/config.xml")
    private val logfile get() = File(proot.filesDir, "syncthing-supervisor.log")

    private fun configured() = binary.isFile && config.isFile

    fun start() {
        if (watcher != null) return
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    lock.withLock { inspect() }
                }.onFailure { Log.w(TAG, "Syncthing supervisor check failed", it) }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Called by the user-visible Reconnect Sync notification action. */
    fun reconnect() {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                if (!configured() || !guestReady()) {
                    Log.i(TAG, "Syncthing not installed/configured; nothing to restart")
                    return@withLock
                }
                restartNow()
            }
        }
    }

    private fun inspect() {
        if (!configured() || !guestReady()) return
        when (connected()) {
            true -> misses = 0
            false -> {
                misses++
                if (misses >= MAX_MISSES && mayRestart()) restartNow()
            }
            null -> {
                // No local API response. Start only if our managed process has
                // exited, to avoid two instances contending for the same DB.
                if (process?.isAlive != true) {
                    misses++
                    if (misses >= 1 && mayRestart()) restartNow()
                }
            }
        }
    }

    private fun mayRestart() = System.currentTimeMillis() - lastRestart >= RESTART_COOLDOWN_MS

    private fun restartNow() {
        val owned = process
        // Shut down an already-running Syncthing gracefully (also works for
        // the older adb-started instance) before starting an owned instance.
        if (connected() != null || owned?.isAlive == true) {
            shutdown()
            if (owned != null && owned.isAlive && !owned.waitFor(15, TimeUnit.SECONDS)) {
                Log.w(TAG, "Previous managed Syncthing still running; refusing duplicate")
                return
            }
            var attempts = 0
            while (connected() != null && attempts++ < 20) Thread.sleep(500)
            if (connected() != null) {
                Log.w(TAG, "Previous Syncthing still owns local API; refusing duplicate")
                return
            }
        }
        process = proot.process(
            "exec /usr/bin/syncthing serve " +
                "--home=/root/.local/state/syncthing --no-browser --no-restart",
        ).apply {
            logfile.parentFile?.mkdirs()
            redirectOutput(ProcessBuilder.Redirect.appendTo(logfile))
        }.start()
        misses = 0
        lastRestart = System.currentTimeMillis()
        Log.i(TAG, "Started phone-owned Syncthing")
    }

    /** null = not running, false = running but M4 peer disconnected. */
    private fun connected(): Boolean? =
        request("GET", "/rest/system/connections")?.let(::anySyncthingPeerConnected)

    private fun shutdown() {
        // Syncthing's API is loopback-only; never use an externally visible GUI.
        request("POST", "/rest/system/shutdown")
    }

    private fun request(method: String, path: String): String? {
        // Read only this fixed local element; do not parse external XML entities.
        val key = runCatching {
            Regex("<apikey>([^<]+)</apikey>").find(config.readText())?.groupValues?.get(1)
        }.getOrNull() ?: return null
        val connection = (URL("http://127.0.0.1:8384" + path).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = method
            connection.setRequestProperty("X-API-Key", key)
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            if (method == "POST") connection.doOutput = true
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "syncthing-supervisor"
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val MAX_MISSES = 3
        private const val RESTART_COOLDOWN_MS = 600_000L
    }
}

/** The REST connection state, independent of any machine-specific device ID. */
internal fun anySyncthingPeerConnected(body: String): Boolean? =
    runCatching {
        val peers = JSONObject(body).getJSONObject("connections")
        val ids = peers.keys()
        var connected = false
        while (ids.hasNext()) {
            if (peers.optJSONObject(ids.next())?.optBoolean("connected", false) == true) {
                connected = true
                break
            }
        }
        connected
    }.getOrNull()
