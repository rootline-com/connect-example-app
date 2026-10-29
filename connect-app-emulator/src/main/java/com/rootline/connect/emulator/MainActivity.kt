package com.rootline.connect.emulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rootline.connect.contract.ConnectWire

/**
 * The control panel. It shows which integrator is connected, sets the outcome of the next payment
 * and the results of the next prints, and simulates a payment that arrives from the backend.
 * Everything here is also available to tests through [ControlReceiver].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = EmulatorStore.get(this)
        setContent {
            MaterialTheme {
                val panel by store.panel.collectAsState()
                ControlPanel(
                    panel = panel,
                    onNextOutcome = { store.nextOutcome = it },
                    onAddPrintResult = { store.printResults = store.printResults + it },
                    onClearPrintResults = { store.printResults = emptyList() },
                    onSimulatePayment = {
                        PaymentSimulator.open(
                            this,
                            amountMinor = ControlReceiver.DEFAULT_AMOUNT_MINOR,
                            currency = ControlReceiver.DEFAULT_CURRENCY,
                            clientId = null,
                            presetOutcome = store.nextOutcome.takeIf { it != EmulatorStore.MANUAL },
                        )
                    },
                    onReset = { store.reset() },
                )
            }
        }
    }
}

@Composable
private fun ControlPanel(
    panel: PanelState,
    onNextOutcome: (String) -> Unit,
    onAddPrintResult: (String) -> Unit,
    onClearPrintResults: () -> Unit,
    onSimulatePayment: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Connect emulator", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "A stand-in for com.rootline.connect, for emulators only. No card reader, printer or backend.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Text(
                    "Integrator: ${panel.replyPackage ?: "none. Call start() from your app."}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            item { Text("Next payment outcome", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (outcome in listOf(EmulatorStore.MANUAL) + PaymentSimulator.OUTCOMES) {
                        FilterChip(
                            selected = panel.nextOutcome == outcome,
                            onClick = { onNextOutcome(outcome) },
                            label = { Text(outcome) },
                        )
                    }
                }
            }

            item { Text("Next print results", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    panel.printResults.ifEmpty { listOf("OK for every print") }.joinToString(" → "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (result in listOf(ConnectWire.PRINT_OK, ConnectWire.PRINT_OUT_OF_PAPER, ConnectWire.PRINT_ERROR)) {
                        OutlinedButton(onClick = { onAddPrintResult(result) }) { Text("+ $result") }
                    }
                }
            }
            item { OutlinedButton(onClick = onClearPrintResults) { Text("Clear print results") } }

            item {
                Button(onClick = onSimulatePayment, modifier = Modifier.fillMaxWidth()) {
                    Text("Simulate incoming payment (€12.99)")
                }
            }
            item {
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
            }

            item { Text("Events", style = MaterialTheme.typography.titleMedium) }
            items(panel.log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
