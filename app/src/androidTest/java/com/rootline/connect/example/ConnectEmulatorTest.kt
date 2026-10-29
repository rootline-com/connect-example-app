package com.rootline.connect.example

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Drives the example app against the connect-app emulator (`:connect-app-emulator`), installed on
 * the same emulator. Each test starts the SDK, lets the emulator simulate a payment that arrives
 * from the backend, and checks what the example app shows.
 *
 * The emulator is controlled with `am broadcast`, through the instrumentation's shell. The
 * emulator only accepts those commands from the shell.
 */
@RunWith(AndroidJUnit4::class)
class ConnectEmulatorTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    // The reset runs before MainActivity launches, so the activity does not restore status from a
    // previous test.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(EmulatorReset()).around(compose)

    @Test
    fun startConnectsToTheEmulator() {
        startConnect()

        compose.onNodeWithTag("connection-status").assertTextEquals("NATS connected")
        compose.onNodeWithTag("runtime-status").assertTextEquals("Runtime: started")
    }

    @Test
    fun succeededPaymentIsShown() {
        startConnect()

        simulatePayment(outcome = "succeeded")

        awaitTag("payment-succeeded")
        compose.onNodeWithTag("payment-succeeded").assertTextContains("12.99 EUR", substring = true)
    }

    @Test
    fun failedPaymentIsShown() {
        startConnect()

        simulatePayment(outcome = "failed")

        awaitTag("payment-failed")
    }

    @Test
    fun cancelledPaymentIsShown() {
        startConnect()

        simulatePayment(outcome = "cancelled")

        awaitTag("payment-cancelled")
    }

    @Test
    fun printerOutOfPaperIsShown() {
        startConnect()
        simulatePayment(outcome = "succeeded")
        awaitTag("payment-succeeded")

        emulator("SET_PRINT_RESULTS --es results OUT_OF_PAPER")
        compose.onNodeWithTag("Customer").performClick()

        awaitTag("last-print")
        compose.onNodeWithTag("last-print").assertTextContains("CUSTOMER → OUT_OF_PAPER", substring = true)
    }

    @Test
    fun disconnectIsShown() {
        startConnect()

        emulator("SET_CONNECTED --ez connected false")

        awaitText("connection-status", "NATS disconnected (simulated)")
    }

    private fun startConnect() {
        compose.onNodeWithTag("Start connect").performClick()
        awaitText("connection-status", "NATS connected")
    }

    private fun simulatePayment(outcome: String) {
        emulator("SIMULATE_PAYMENT --es outcome $outcome --el amount_minor 1299 --es currency EUR")
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(TIMEOUT_MS) { isShown(hasTestTag(tag)) }
    }

    private fun awaitText(tag: String, text: String) {
        compose.waitUntil(TIMEOUT_MS) { isShown(hasTestTag(tag) and hasText(text)) }
    }

    private fun isShown(matcher: SemanticsMatcher): Boolean =
        try {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            // While a screen of the connect-app emulator is in front (start, card prompt), this app
            // has no focused Compose hierarchy and the lookup throws. That means "not yet".
            false
        }

    /** Clears the emulator and the example app's stored status, and checks the emulator is installed. */
    private class EmulatorReset : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val emulatorVersion = try {
                context.packageManager.getPackageInfo(CONNECT_PACKAGE, 0).versionName.orEmpty()
            } catch (e: Exception) {
                throw AssertionError(
                    "$CONNECT_PACKAGE is not installed. Run ./gradlew :connect-app-emulator:installDebug first.",
                    e,
                )
            }
            check(emulatorVersion.startsWith("emulator-")) {
                "$CONNECT_PACKAGE $emulatorVersion is the real connect-app, not the emulator."
            }

            emulator("RESET")
            // StatusStore's file: the last seen connection and runtime status.
            context.getSharedPreferences("connect-example", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private companion object {
        const val CONNECT_PACKAGE = "com.rootline.connect"
        const val TIMEOUT_MS = 15_000L

        /** Sends one control command to the emulator and waits until the broadcast is delivered. */
        fun emulator(command: String) {
            val action = command.substringBefore(' ')
            val extras = command.substringAfter(' ', missingDelimiterValue = "")
            val shell = "am broadcast -p $CONNECT_PACKAGE -a com.rootline.connect.emulator.action.$action $extras"
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(shell)
                .use { descriptor ->
                    // am returns when the broadcast is delivered; reading to the end waits for that.
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
        }
    }
}
