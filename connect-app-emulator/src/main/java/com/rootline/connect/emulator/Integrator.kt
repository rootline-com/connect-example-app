package com.rootline.connect.emulator

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.putPayload
import com.squareup.wire.Message

/**
 * How the emulator identifies the integrator's app and talks back to it. This follows the real
 * connect-app, except that there is no allowlist: any app that proves its package can connect.
 */
object Integrator {

    /** The integrator's return PendingIntent. The SDK attaches it to start, requestPayment and printReceipt. */
    fun returnPendingIntent(intent: Intent): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ConnectWire.EXTRA_RETURN_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ConnectWire.EXTRA_RETURN_PENDING_INTENT)
        }

    /**
     * True when the request comes from the integrator that called start(). The Android system sets
     * a PendingIntent's creator package, so another app cannot fake it.
     */
    fun isRegisteredCaller(intent: Intent, store: EmulatorStore): Boolean {
        val creator = returnPendingIntent(intent)?.creatorPackage
        return creator != null && creator == store.replyPackage
    }

    /**
     * Sends a status broadcast. The sender token proves to the SDK that the broadcast comes from
     * com.rootline.connect; the SDK drops a broadcast without it.
     */
    fun sendStatus(context: Context, targetPackage: String, action: String, message: Message<*, *>) {
        val intent = Intent(action)
            .setPackage(targetPackage)
            .putExtra(ConnectWire.EXTRA_SENDER_TOKEN, senderToken(context))
        ConnectWire.putPayload(intent, message)
        context.sendBroadcast(intent)
    }

    /**
     * Brings the integrator's app to the front, so that the SDK drains the outbox. Best effort:
     * Android can refuse an activity start from the background.
     */
    fun bringForward(context: Context, store: EmulatorStore) {
        val pendingIntent = store.returnPendingIntent
        if (pendingIntent != null) {
            try {
                pendingIntent.send(context, 0, null, null, null, null, allowActivityStartOptions())
                return
            } catch (e: PendingIntent.CanceledException) {
                store.log("Return intent of the integrator is no longer valid")
            }
        }
        val packageName = store.replyPackage ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            store.log("Android refused to bring $packageName to the front")
        }
    }

    /**
     * From Android 14, a sender must opt in before its PendingIntent may start an activity, or
     * Android drops the start without an error. This works only while the emulator itself is in
     * front, for example after a tap on "Simulate incoming payment" in the control panel.
     */
    private fun allowActivityStartOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        @Suppress("DEPRECATION") // ALLOW_IF_VISIBLE replaces it on API 36; ALLOWED still works there.
        val mode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    /**
     * An immutable PendingIntent that is never sent. Its creator package is this app, which is the
     * identity the SDK checks on each status broadcast. Same construction as the real connect-app.
     */
    private fun senderToken(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent("com.rootline.connect.action.SENDER_TOKEN").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
