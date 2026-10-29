package com.rootline.connect.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rootline.connect.contract.ConnectCodec
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.PrintReceiptRequest
import com.rootline.connect.contract.v1.PrintResult

/**
 * Handles the SDK's printReceipt(). Nothing prints. The result is the next value from the
 * print-result queue in the control panel, or OK when the queue is empty. A receipt exists only for
 * a payment that succeeded; any other payment id gets NOT_FOUND.
 */
class PrintRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = EmulatorStore.get(context)
        if (!Integrator.isRegisteredCaller(intent, store)) {
            store.log("Ignored printReceipt(): caller did not call start()")
            return
        }
        val request = intent.getStringExtra(ConnectWire.EXTRA_PAYLOAD)
            ?.let { ConnectCodec.decode(it, PrintReceiptRequest::class.java) }
            ?: return
        val paymentId = request.payment_id.orEmpty()

        val printOutcome = if (store.payment(paymentId)?.status == ConnectWire.OUTCOME_SUCCEEDED) {
            store.takePrintResult()
        } else {
            ConnectWire.PRINT_NOT_FOUND
        }
        store.enqueuePrint(
            PrintResult(
                payment_id = paymentId,
                recipient = request.recipient,
                print_outcome = printOutcome,
                request_id = request.request_id,
            ),
        )
        store.log("Print ${request.recipient} receipt of $paymentId: $printOutcome")
    }
}
