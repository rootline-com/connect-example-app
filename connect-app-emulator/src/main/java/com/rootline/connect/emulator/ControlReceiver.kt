package com.rootline.connect.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.ConnectionState

/**
 * Test controls, sent with `adb shell am broadcast`. The manifest protects this receiver with the
 * DUMP permission, so the adb shell and instrumentation tests can use it, but other apps cannot.
 *
 * ```
 * am broadcast -a com.rootline.connect.emulator.action.SIMULATE_PAYMENT --es outcome succeeded --el amount_minor 1299 --es currency EUR
 * am broadcast -a com.rootline.connect.emulator.action.SET_NEXT_OUTCOME --es outcome manual
 * am broadcast -a com.rootline.connect.emulator.action.SET_PRINT_RESULTS --es results OK,OUT_OF_PAPER
 * am broadcast -a com.rootline.connect.emulator.action.SET_CONNECTED --ez connected false
 * am broadcast -a com.rootline.connect.emulator.action.RESET
 * ```
 *
 * Each command also needs `-p com.rootline.connect`, because Android does not deliver an implicit
 * broadcast to a manifest receiver.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = EmulatorStore.get(context)
        when (intent.action) {
            ACTION_SIMULATE_PAYMENT -> {
                val outcome = intent.getStringExtra(EXTRA_OUTCOME) ?: store.nextOutcome
                if (!isValidOutcome(outcome)) {
                    store.log("Ignored simulate: unknown outcome '$outcome'")
                    return
                }
                PaymentSimulator.open(
                    context,
                    amountMinor = intent.getLongExtra(EXTRA_AMOUNT_MINOR, DEFAULT_AMOUNT_MINOR),
                    currency = intent.getStringExtra(EXTRA_CURRENCY) ?: DEFAULT_CURRENCY,
                    clientId = null,
                    presetOutcome = outcome.takeIf { it != EmulatorStore.MANUAL },
                )
            }

            ACTION_SET_NEXT_OUTCOME -> {
                val outcome = intent.getStringExtra(EXTRA_OUTCOME).orEmpty()
                if (!isValidOutcome(outcome)) {
                    store.log("Ignored next outcome: unknown outcome '$outcome'")
                    return
                }
                store.nextOutcome = outcome
                store.log("Next outcome: $outcome")
            }

            ACTION_SET_PRINT_RESULTS -> {
                store.printResults = intent.getStringExtra(EXTRA_RESULTS).orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                store.log("Print results: ${store.printResults.ifEmpty { listOf("OK") }.joinToString()}")
            }

            ACTION_SET_CONNECTED -> {
                val connected = intent.getBooleanExtra(EXTRA_CONNECTED, true)
                val replyPackage = store.replyPackage ?: run {
                    store.log("Ignored connection change: no integrator called start()")
                    return
                }
                val state = ConnectionState(connected = connected, reason = if (connected) null else "simulated")
                Integrator.sendStatus(context, replyPackage, ConnectWire.ACTION_CONNECTION_STATE, state)
                store.log(if (connected) "Connection: connected" else "Connection: disconnected")
            }

            ACTION_RESET -> {
                store.reset()
                store.log("Reset")
            }
        }
    }

    private fun isValidOutcome(outcome: String): Boolean =
        outcome == EmulatorStore.MANUAL || outcome in PaymentSimulator.OUTCOMES

    companion object {
        const val ACTION_SIMULATE_PAYMENT = "com.rootline.connect.emulator.action.SIMULATE_PAYMENT"
        const val ACTION_SET_NEXT_OUTCOME = "com.rootline.connect.emulator.action.SET_NEXT_OUTCOME"
        const val ACTION_SET_PRINT_RESULTS = "com.rootline.connect.emulator.action.SET_PRINT_RESULTS"
        const val ACTION_SET_CONNECTED = "com.rootline.connect.emulator.action.SET_CONNECTED"
        const val ACTION_RESET = "com.rootline.connect.emulator.action.RESET"

        const val EXTRA_OUTCOME = "outcome"
        const val EXTRA_AMOUNT_MINOR = "amount_minor"
        const val EXTRA_CURRENCY = "currency"
        const val EXTRA_RESULTS = "results"
        const val EXTRA_CONNECTED = "connected"

        const val DEFAULT_AMOUNT_MINOR = 1299L
        const val DEFAULT_CURRENCY = "EUR"
    }
}
