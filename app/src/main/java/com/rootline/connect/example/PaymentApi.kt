package com.rootline.connect.example

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

private const val PAYMENT_API_URL = "https://payment-api.sandbox.rootline.com"
private const val API_VERSION = "2024-04-23"

// All three come from the gitignored local.properties via BuildConfig, so no real credential or
// identifier lives in the repo. See "Payment API configuration" in the README.

/** Secret key. Empty unless `paymentApiKey` is set locally, which [PaymentApi] reports as an error. */
private val API_KEY: String = BuildConfig.PAYMENT_API_KEY

/** The account the payment is created on. The namespace-scoped key can serve several accounts. */
private val ACCOUNT_ID: String = BuildConfig.PAYMENT_ACCOUNT_ID

/**
 * The terminal to charge on. Sending it in `payment_rails.payment_device_id` is what makes the backend
 * route the payment to that terminal as a card-present prompt instead of hosted checkout.
 */
private val PAYMENT_DEVICE_ID: String = BuildConfig.PAYMENT_DEVICE_ID

/**
 * Direct `POST /v1/payments` against the Rootline Payment API, replacing the SDK's `requestPayment`.
 * The terminal still drives the card prompt and still reports outcomes back over the SDK's broadcast
 * handlers — only the *initiation* moves off the SDK, because `payment_rails.payment_device_id` is
 * what routes the payment to this terminal instead of hosted checkout.
 *
 * Blocking on purpose: callers run it on a background dispatcher.
 */
object PaymentApi {

    /** Creates a card-present payment. Returns the payment id on success. */
    fun createPayment(amountMinor: Long, currency: String): Result<String> = runCatching {
        if (API_KEY.isEmpty()) {
            error("no paymentApiKey in local.properties")
        }

        val body = JSONObject().apply {
            put("account_id", ACCOUNT_ID)
            put("reference", "ref-${UUID.randomUUID()}")
            put(
                "amount",
                JSONObject().apply {
                    put("currency", currency)
                    put("quantity", quantityOf(amountMinor))
                },
            )
            put("payment_rails", JSONObject().put("payment_device_id", PAYMENT_DEVICE_ID))
        }.toString()

        val connection = (URL("$PAYMENT_API_URL/v1/payments").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Api-Key", API_KEY)
            setRequestProperty("Rootline-Version", API_VERSION)
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (status !in 200..299) {
                error("HTTP $status ${response.take(200).ifEmpty { "(empty body)" }}")
            }
            JSONObject(response).optString("id").ifEmpty { "created" }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Minor units to the API's `amount.quantity` — a string with exactly two decimals. Done with integer
 * arithmetic and [Locale.ROOT] so a comma-decimal device locale can't emit "12,99", which the API
 * rejects.
 */
private fun quantityOf(amountMinor: Long): String =
    "${amountMinor / 100}.${String.format(Locale.ROOT, "%02d", amountMinor % 100)}"
