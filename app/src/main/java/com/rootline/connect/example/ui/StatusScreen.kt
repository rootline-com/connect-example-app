package com.rootline.connect.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.rootline.connect.sdk.PaymentOutcome
import com.rootline.connect.sdk.ReceiptRecipient
import com.rootline.connect.example.ConnectUiState
import com.rootline.connect.example.PaymentLine
import com.rootline.connect.example.ui.theme.AppButton
import com.rootline.connect.example.ui.theme.ConnectedGreen
import com.rootline.connect.example.ui.theme.ErrorRed

/**
 * The entire example UI: start the connect runtime, a live NATS indicator, the broadcast payment
 * feed, and the SDK receipt-print API — print a succeeded payment's customer/merchant receipt.
 */
@Composable
fun StatusScreen(
    state: ConnectUiState,
    onStart: () -> Unit,
    onRequestPayment: () -> Unit = {},
    onPrintReceipt: (String, ReceiptRecipient) -> Unit = { _, _ -> },
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(text = "Connect Example", style = MaterialTheme.typography.titleLarge) }
            item { AppButton(text = "Start connect", onClick = onStart, modifier = Modifier.fillMaxWidth()) }
            item {
                AppButton(
                    text = "Request payment (€12.99)",
                    onClick = onRequestPayment,
                    modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("request-payment"),
                )
            }
            item { ConnectionIndicator(state) }
            item { RuntimeStatus(state) }

            if (state.lastRequest != null) {
                item {
                    Text(
                        text = "Payment request: ${state.lastRequest}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("last-request"),
                    )
                }
            }

            if (state.lastPrint != null) {
                item {
                    Text(
                        text = "Last print: ${state.lastPrint}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("last-print"),
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { Text(text = "Payments", style = MaterialTheme.typography.titleMedium) }
            if (state.payments.isEmpty()) {
                item { EmptyHint("No payments yet") }
            } else {
                items(state.payments, key = { it.id }) { line -> PaymentRow(line, onPrintReceipt) }
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(state: ConnectUiState) {
    val color = when (state.connected) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        true -> ConnectedGreen
        false -> ErrorRed
    }
    val status = when {
        state.connected == null -> "NATS state unknown"
        state.connected -> "NATS connected"
        state.connectionReason != null -> "NATS disconnected (${state.connectionReason})"
        else -> "NATS disconnected"
    }
    // A restored value is the last one broadcast, not one confirmed this run — say so rather than
    // dressing stale status as live. Cleared as soon as a broadcast arrives.
    val label = if (state.connectionStale) "$status · last known" else status
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("connection-status"),
        )
    }
}

@Composable
private fun RuntimeStatus(state: ConnectUiState) {
    val status = when (state.runtimeStarted) {
        null -> "Runtime: not started"
        true -> "Runtime: started"
        false -> "Runtime: failed (${state.startReason ?: "unknown"})"
    }
    val label = if (state.runtimeStale) "$status · last known" else status
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("runtime-status"),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PaymentRow(line: PaymentLine, onPrintReceipt: (String, ReceiptRecipient) -> Unit) {
    val amount = "%.2f %s".format(line.amountMinor / 100.0, line.currency)
    val outcome = line.outcome.label()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${line.id} · $outcome · $amount",
            color = outcomeColor(line.outcome),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("payment-$outcome"),
        )
        if (line.outcome == PaymentOutcome.Succeeded) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "Customer",
                    onClick = { onPrintReceipt(line.id, ReceiptRecipient.CUSTOMER) },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "Merchant",
                    onClick = { onPrintReceipt(line.id, ReceiptRecipient.MERCHANT) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Display token for a payment outcome, degrading an unknown value to its raw wire string. */
private fun PaymentOutcome.label(): String = when (this) {
    PaymentOutcome.Succeeded -> "succeeded"
    PaymentOutcome.Failed -> "failed"
    PaymentOutcome.Cancelled -> "cancelled"
    is PaymentOutcome.Unknown -> raw
}

private fun outcomeColor(outcome: PaymentOutcome): Color = when (outcome) {
    PaymentOutcome.Succeeded -> ConnectedGreen
    PaymentOutcome.Failed, PaymentOutcome.Cancelled -> ErrorRed
    else -> Color.Unspecified
}
