package com.rootline.connect.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rootline.connect.contract.ConnectCodec
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.PaymentRequest

/**
 * Handles the SDK's requestPayment(). On a terminal the connect-app creates the payment on the
 * backend first. Here the payment opens at once and continues like a simulated incoming payment,
 * with the outcome chosen in the control panel.
 */
class PaymentRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = EmulatorStore.get(context)
        if (!Integrator.isRegisteredCaller(intent, store)) {
            store.log("Ignored requestPayment(): caller did not call start()")
            return
        }
        val request = intent.getStringExtra(ConnectWire.EXTRA_PAYLOAD)
            ?.let { ConnectCodec.decode(it, PaymentRequest::class.java) }
            ?: return

        val currency = request.currency.orEmpty()
        // The backend refuses these, and the connect-app reports that refusal as a failed outcome.
        if (request.amount_minor <= 0 || currency.isBlank()) {
            PaymentSimulator.reject(context, request.amount_minor, currency, request.client_id, "amount or currency missing")
            return
        }

        val presetOutcome = store.nextOutcome.takeIf { it != EmulatorStore.MANUAL }
        PaymentSimulator.open(context, request.amount_minor, currency, request.client_id, presetOutcome)
    }
}
