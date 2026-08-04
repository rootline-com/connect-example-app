package com.rootline.connect.example

import com.rootline.connect.sdk.ConnectionState
import com.rootline.connect.sdk.PaymentOutcome
import com.rootline.connect.sdk.PaymentState
import com.rootline.connect.sdk.RuntimeStarted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectClientTest {

    private fun payment(
        id: String,
        outcome: PaymentOutcome,
        amount: Long = 1000L,
        currency: String = "EUR",
        clientId: String? = null,
    ) = PaymentState(id = id, outcome = outcome, amountMinor = amount, currency = currency, clientId = clientId)

    @Test
    fun startedSuccessMarksRuntimeStarted() {
        val state = ConnectClient.reduce(ConnectUiState(), RuntimeStarted(success = true, reason = null))
        assertEquals(true, state.runtimeStarted)
        assertNull(state.startReason)
    }

    @Test
    fun startedFailureCarriesReason() {
        val state = ConnectClient.reduce(
            ConnectUiState(),
            RuntimeStarted(success = false, reason = "TimeoutCancellationException"),
        )
        assertEquals(false, state.runtimeStarted)
        assertEquals("TimeoutCancellationException", state.startReason)
    }

    @Test
    fun connectionStateConnected() {
        val state = ConnectClient.reduce(ConnectUiState(), ConnectionState(connected = true, reason = null))
        assertTrue(state.connected)
        assertNull(state.connectionReason)
    }

    @Test
    fun connectionStateDisconnectedWithReason() {
        val state = ConnectClient.reduce(
            ConnectUiState(connected = true),
            ConnectionState(connected = false, reason = "IOException"),
        )
        assertFalse(state.connected)
        assertEquals("IOException", state.connectionReason)
    }

    @Test
    fun terminalPaymentIsOneEntryAndEchoesClientId() {
        val state = ConnectClient.reduce(
            ConnectUiState(),
            payment("pay_1", PaymentOutcome.Succeeded, clientId = "order-42"),
        )
        val line = state.payments.single()
        assertEquals("pay_1", line.id)
        assertEquals(PaymentOutcome.Succeeded, line.outcome)
        assertEquals(1000L, line.amountMinor)
        assertEquals("EUR", line.currency)
        assertEquals("order-42", line.clientId)
    }

    @Test
    fun aReplayedOutcomeForTheSameIdStaysOneEntry() {
        // At-least-once delivery can replay the same outcome; the feed must dedup by id.
        var state = ConnectClient.reduce(ConnectUiState(), payment("pay_1", PaymentOutcome.Succeeded))
        state = ConnectClient.reduce(state, payment("pay_1", PaymentOutcome.Succeeded))
        assertEquals(1, state.payments.size)
        assertEquals(PaymentOutcome.Succeeded, state.payments.single().outcome)
    }

    @Test
    fun eachTerminalOutcomeIsRendered() {
        for (outcome in listOf(PaymentOutcome.Succeeded, PaymentOutcome.Failed, PaymentOutcome.Cancelled)) {
            val state = ConnectClient.reduce(ConnectUiState(), payment("pay_x", outcome))
            assertEquals(outcome, state.payments.single().outcome)
        }
    }

    @Test
    fun multiplePaymentsAreNewestFirst() {
        var state = ConnectClient.reduce(ConnectUiState(), payment("pay_1", PaymentOutcome.Succeeded))
        state = ConnectClient.reduce(state, payment("pay_2", PaymentOutcome.Failed))
        assertEquals(listOf("pay_2", "pay_1"), state.payments.map { it.id })

        // A later outcome for pay_1 moves it back to the front.
        state = ConnectClient.reduce(state, payment("pay_1", PaymentOutcome.Cancelled))
        assertEquals(listOf("pay_1", "pay_2"), state.payments.map { it.id })
    }
}
