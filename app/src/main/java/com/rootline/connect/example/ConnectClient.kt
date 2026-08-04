package com.rootline.connect.example

import com.rootline.connect.sdk.ConnectionState
import com.rootline.connect.sdk.PaymentOutcome
import com.rootline.connect.sdk.PaymentState
import com.rootline.connect.sdk.PrintOutcome
import com.rootline.connect.sdk.PrintResult
import com.rootline.connect.sdk.RuntimeStarted

/**
 * Pure reducer folding the connect-app's typed results (delivered by the SDK's [RootlineConnect]
 * handlers) into the example app's UI state. Holds only state transitions, so it unit-tests on the
 * JVM without a device — all Android coupling lives in [MainActivity]. Each SDK handler has its own
 * typed result, so [reduce] is overloaded per type rather than switching on a union.
 */
object ConnectClient {

    fun reduce(state: ConnectUiState, event: RuntimeStarted): ConnectUiState =
        state.copy(runtimeStarted = event.success, startReason = event.reason)

    fun reduce(state: ConnectUiState, event: ConnectionState): ConnectUiState =
        state.copy(connected = event.connected, connectionReason = event.reason)

    fun reduce(state: ConnectUiState, event: PaymentState): ConnectUiState {
        val line = PaymentLine(
            id = event.id,
            outcome = event.outcome,
            amountMinor = event.amountMinor,
            currency = event.currency,
            clientId = event.clientId,
        )
        return state.copy(payments = state.payments.upsert(line))
    }

    fun reduce(state: ConnectUiState, event: PrintResult): ConnectUiState {
        val target = "${event.paymentId} ${event.recipient}".trim().ifEmpty { "?" }
        return state.copy(lastPrint = "$target → ${event.outcome.label()}")
    }

    /**
     * One entry per payment id, newest first. A repeated outcome for an id we already hold replaces
     * that entry in place and moves it to the front, so the feed shows one row per payment.
     */
    private fun List<PaymentLine>.upsert(line: PaymentLine): List<PaymentLine> {
        val withoutExisting = filterNot { it.id == line.id }
        return listOf(line) + withoutExisting
    }
}

/** Display token for a print outcome, degrading an unknown value to its raw wire string. */
private fun PrintOutcome.label(): String = when (this) {
    PrintOutcome.Ok -> "OK"
    PrintOutcome.OutOfPaper -> "OUT_OF_PAPER"
    PrintOutcome.Overheated -> "OVERHEATED"
    PrintOutcome.LowVoltage -> "LOW_VOLTAGE"
    PrintOutcome.NoPrinter -> "NO_PRINTER"
    PrintOutcome.NotFound -> "NOT_FOUND"
    PrintOutcome.Error -> "ERROR"
    is PrintOutcome.Unknown -> raw
}

data class ConnectUiState(
    val connected: Boolean = false,
    val connectionReason: String? = null,
    val runtimeStarted: Boolean? = null,
    val startReason: String? = null,
    val payments: List<PaymentLine> = emptyList(),
    /** Last print outcome reported via onPrintResult, for the print buttons' feedback. */
    val lastPrint: String? = null,
    /**
     * Outcome of the last Payment API create call. Local to this screen rather than folded from an
     * SDK event, because payment initiation no longer goes through the SDK.
     */
    val lastRequest: String? = null,
)

data class PaymentLine(
    val id: String,
    val outcome: PaymentOutcome,
    val amountMinor: Long,
    val currency: String,
    /** The requestPayment client_id echoed back, when this integrator initiated the payment. */
    val clientId: String? = null,
)
