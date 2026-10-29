package com.rootline.connect.emulator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.rootline.connect.contract.ConnectCodec
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.PresentPaymentRequest
import kotlinx.coroutines.delay

/**
 * The simulated card prompt. The SDK starts it with PRESENT_PAYMENT when it reads a present trigger
 * from the outbox. A payment with a preset outcome completes after a short pause; any other payment
 * waits for a tap. When the payment is decided, this screen closes and the integrator's app is in
 * front again, where the SDK reads the outcome.
 */
class PaymentPresenterActivity : ComponentActivity() {

    private var payment by mutableStateOf<SimulatedPayment?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        present(intent)
        setContent {
            MaterialTheme {
                payment?.let { CardPrompt(it, onDecide = ::decide) }
            }
        }
    }

    // singleTop: a second present trigger arrives here instead of in a new instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        present(intent)
    }

    private fun present(intent: Intent) {
        val paymentId = intent.getStringExtra(ConnectWire.EXTRA_PAYLOAD)
            ?.let { ConnectCodec.decode(it, PresentPaymentRequest::class.java) }
            ?.payment_id
        // Unknown, timed out or already decided: nothing to show.
        val presented = paymentId?.let { PaymentSimulator.markPresented(this, it) }
        if (presented == null) {
            if (payment == null) {
                finish()
            }
            return
        }
        payment = presented
    }

    private fun decide(outcome: String) {
        val current = payment ?: return
        PaymentSimulator.complete(this, current.id, outcome)
        finish()
    }
}

@Composable
private fun CardPrompt(payment: SimulatedPayment, onDecide: (String) -> Unit) {
    val preset = payment.presetOutcome
    if (preset != null) {
        LaunchedEffect(payment.id) {
            delay(AUTO_COMPLETE_DELAY_MS)
            onDecide(preset)
        }
    }
    // Back on a terminal card prompt cancels the payment.
    BackHandler { onDecide(ConnectWire.OUTCOME_CANCELLED) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Simulated card reader", style = MaterialTheme.typography.titleMedium)
            Text(
                PaymentSimulator.formatAmount(payment.amountMinor, payment.currency),
                style = MaterialTheme.typography.displayMedium,
            )
            Text(payment.id, style = MaterialTheme.typography.bodySmall)
            if (preset != null) {
                Text("Completes as $preset…", style = MaterialTheme.typography.bodyLarge)
            } else {
                DecisionButton("Approve", primary = true) { onDecide(ConnectWire.OUTCOME_SUCCEEDED) }
                DecisionButton("Decline") { onDecide(ConnectWire.OUTCOME_FAILED) }
                DecisionButton("Cancel") { onDecide(ConnectWire.OUTCOME_CANCELLED) }
            }
        }
    }
}

@Composable
private fun DecisionButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    // A resource id per button, so UiAutomator and Maestro can tap it.
    val modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag(text)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

private const val AUTO_COMPLETE_DELAY_MS = 1_000L
