package com.rootline.connect.example

import android.content.Context
import com.rootline.connect.sdk.ConnectionState
import com.rootline.connect.sdk.RuntimeStarted

/**
 * Remembers the last status the connect-app broadcast — NATS reachability and the runtime start
 * result — so a relaunch renders those values instead of falling back to "disconnected"/"not
 * started".
 *
 * Status is ok-to-miss, unlike the durable payment/print outcomes the SDK replays from its outbox:
 * connect-app emits connection state when NATS changes and when an integrator registers via
 * `start()`, and nothing re-sends it on demand. An integrator that is backgrounded and restarted
 * therefore has no value to show until the next change — and a `false`/`not started` default reads
 * as a genuine disconnect or an unstarted runtime. Keeping the last seen value on disk is the honest
 * alternative, provided the UI marks it as unconfirmed (see [ConnectUiState.connectionStale] and
 * [ConnectUiState.runtimeStale]) rather than presenting stale status as live.
 *
 * Android-coupled by design; the folds themselves stay in the pure [ConnectClient] reducer.
 */
class StatusStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Writes through on every broadcast. Async ([android.content.SharedPreferences.Editor.apply]) — handlers run on the main thread. */
    fun save(event: ConnectionState) {
        prefs.edit()
            .putBoolean(KEY_CONNECTED, event.connected)
            .putString(KEY_CONNECTION_REASON, event.reason)
            .apply()
    }

    fun save(event: RuntimeStarted) {
        prefs.edit()
            .putBoolean(KEY_RUNTIME_STARTED, event.success)
            .putString(KEY_START_REASON, event.reason)
            .apply()
    }

    /**
     * Seeds [state] with the last stored values, flagged stale. A value that was never stored is left
     * at its default, so a first run shows "unknown"/"not started" rather than something we never
     * received. The two statuses are restored independently — they arrive from separate broadcasts.
     */
    fun restore(state: ConnectUiState): ConnectUiState {
        var restored = state
        if (prefs.contains(KEY_CONNECTED)) {
            restored = restored.copy(
                connected = prefs.getBoolean(KEY_CONNECTED, false),
                connectionReason = prefs.getString(KEY_CONNECTION_REASON, null),
                connectionStale = true,
            )
        }
        if (prefs.contains(KEY_RUNTIME_STARTED)) {
            restored = restored.copy(
                runtimeStarted = prefs.getBoolean(KEY_RUNTIME_STARTED, false),
                startReason = prefs.getString(KEY_START_REASON, null),
                runtimeStale = true,
            )
        }
        return restored
    }

    private companion object {
        private const val PREFS_NAME = "connect-example"
        private const val KEY_CONNECTED = "connection.connected"
        private const val KEY_CONNECTION_REASON = "connection.reason"
        private const val KEY_RUNTIME_STARTED = "runtime.started"
        private const val KEY_START_REASON = "runtime.reason"
    }
}
