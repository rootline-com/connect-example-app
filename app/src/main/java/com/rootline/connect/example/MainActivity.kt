package com.rootline.connect.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.rootline.connect.sdk.RootlineConnect
import com.rootline.connect.example.ui.StatusScreen
import com.rootline.connect.example.ui.theme.ExampleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole example app: start the connect runtime through the SDK, initiate a payment, and render
 * the connection and payment state the connect-app pushes back. This is the integrator-facing surface
 * — there is no payment logic here. [RootlineConnect] owns the reply receiver and the durable outcome
 * outbox (both tied to this Activity's lifecycle) and fans each result to a handler; [ConnectClient]
 * folds them into the UI state.
 */
class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(ConnectUiState())

    // RootlineConnect ties its reply receiver + outbox observer to this Activity's lifecycle, so
    // there is nothing to register or unregister by hand.
    private val connect = RootlineConnect(this)
        .onRuntimeStarted { uiState = ConnectClient.reduce(uiState, it) }
        .onConnectionState { uiState = ConnectClient.reduce(uiState, it) }
        .onPaymentState { uiState = ConnectClient.reduce(uiState, it) }
        .onPrintResult { uiState = ConnectClient.reduce(uiState, it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ExampleTheme {
                StatusScreen(
                    state = uiState,
                    onStart = { connect.start() },
                    onRequestPayment = { requestPayment(amountMinor = 1299, currency = "EUR") },
                    onPrintReceipt = { paymentId, recipient -> connect.printReceipt(paymentId, recipient) },
                )
            }
        }
    }

    /**
     * Creates the payment over the Payment API instead of the SDK. The terminal still prompts and
     * still reports the outcome back through [RootlineConnect]'s handlers, so the payment feed below
     * fills in from the broadcast — this only replaces initiation.
     */
    private fun requestPayment(amountMinor: Long, currency: String) {
        uiState = uiState.copy(lastRequest = "creating payment…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { PaymentApi.createPayment(amountMinor, currency) }
            uiState = uiState.copy(
                lastRequest = result.fold(
                    onSuccess = { "created $it" },
                    onFailure = { "failed: ${it.message ?: it::class.simpleName}" },
                ),
            )
        }
    }
}
