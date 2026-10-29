package com.rootline.connect.emulator

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.PaymentState
import com.rootline.connect.contract.v1.PresentPayment
import java.util.UUID

/**
 * The life of one simulated payment, in the same order as on a terminal:
 *
 * 1. [open] writes a present trigger to the outbox. The SDK reads it and starts
 *    [PaymentPresenterActivity].
 * 2. [markPresented] records that the card screen is up.
 * 3. [complete] writes the outcome to the outbox. The SDK delivers it to `onPaymentState`.
 *
 * A payment that nobody presents within 10 seconds fails, as on a terminal.
 */
object PaymentSimulator {

    const val STATUS_OPEN = "open"
    const val STATUS_PRESENTED = "presented"

    /** The outcomes a test can choose. */
    val OUTCOMES = listOf(ConnectWire.OUTCOME_SUCCEEDED, ConnectWire.OUTCOME_FAILED, ConnectWire.OUTCOME_CANCELLED)

    private const val PRESENT_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Opens a payment and asks the integrator to present it. Returns the payment id, or null when no
     * integrator has called start(), because then nothing can present the payment.
     *
     * @param presetOutcome the outcome to complete with without a tap, or null to wait for a tap.
     */
    fun open(
        context: Context,
        amountMinor: Long,
        currency: String,
        clientId: String?,
        presetOutcome: String?,
    ): String? {
        val store = EmulatorStore.get(context)
        if (store.replyPackage == null) {
            store.log("No payment opened: no integrator called start()")
            return null
        }

        val id = "pay_emu_" + UUID.randomUUID().toString().replace("-", "").take(12)
        store.insertPayment(
            SimulatedPayment(id, amountMinor, currency, clientId, presetOutcome, STATUS_OPEN),
        )
        store.enqueuePresent(PresentPayment(payment_id = id, opened_at_millis = System.currentTimeMillis()))
        store.log("Opened $id, ${formatAmount(amountMinor, currency)}, outcome ${presetOutcome ?: "by tap"}")

        mainHandler.postDelayed({ failIfNotPresented(context, id) }, PRESENT_TIMEOUT_MS)
        Integrator.bringForward(context, store)
        return id
    }

    /** Marks the payment as on screen. Returns null when the payment is unknown or already decided. */
    fun markPresented(context: Context, id: String): SimulatedPayment? {
        val store = EmulatorStore.get(context)
        if (!store.transitionPayment(id, STATUS_OPEN, STATUS_PRESENTED)) {
            return null
        }
        return store.payment(id)
    }

    /** Decides a presented payment and writes its outcome for the SDK. A second decision is ignored. */
    fun complete(context: Context, id: String, outcome: String) {
        decide(context, id, STATUS_PRESENTED, outcome)
    }

    /** Writes a failed outcome for a request that never became a payment, for example a zero amount. */
    fun reject(context: Context, amountMinor: Long, currency: String, clientId: String?, reason: String) {
        val store = EmulatorStore.get(context)
        val id = "pay_emu_" + UUID.randomUUID().toString().replace("-", "").take(12)
        store.enqueuePayment(
            PaymentState(
                payment_id = id,
                outcome = ConnectWire.OUTCOME_FAILED,
                amount_minor = amountMinor,
                currency = currency,
                client_id = clientId,
            ),
        )
        store.log("Rejected payment request: $reason")
    }

    private fun failIfNotPresented(context: Context, id: String) {
        decide(context, id, STATUS_OPEN, ConnectWire.OUTCOME_FAILED, reason = "not presented within 10 s")
    }

    private fun decide(context: Context, id: String, from: String, outcome: String, reason: String? = null) {
        val store = EmulatorStore.get(context)
        if (!store.transitionPayment(id, from, outcome)) {
            return
        }
        val payment = store.payment(id) ?: return
        store.enqueuePayment(
            PaymentState(
                payment_id = payment.id,
                outcome = outcome,
                amount_minor = payment.amountMinor,
                currency = payment.currency,
                client_id = payment.clientId,
            ),
        )
        store.log(if (reason == null) "$id $outcome" else "$id $outcome: $reason")
    }

    fun formatAmount(amountMinor: Long, currency: String): String =
        "%.2f %s".format(amountMinor / 100.0, currency)
}
