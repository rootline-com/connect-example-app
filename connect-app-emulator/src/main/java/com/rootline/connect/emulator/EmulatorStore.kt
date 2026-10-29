package com.rootline.connect.emulator

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.rootline.connect.contract.ConnectCodec
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.OutboxEvent
import com.rootline.connect.contract.v1.PaymentState
import com.rootline.connect.contract.v1.PresentPayment
import com.rootline.connect.contract.v1.PrintResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Everything the emulator remembers. The outbox and the simulated payments live in SQLite, because
 * the SDK replays outbox rows after a restart. The test settings live in SharedPreferences. The
 * event log and the integrator's return PendingIntent live only in this process.
 *
 * The receivers, the activities and the outbox provider all run in this one process and share the
 * single instance from [get].
 */
class EmulatorStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = Database(appContext)
    private val prefs = appContext.getSharedPreferences("connect-emulator", Context.MODE_PRIVATE)
    private val outboxUri = Uri.parse("content://${appContext.packageName}.outbox/outcomes")
    private val log = ArrayDeque<String>()

    private val _panel = MutableStateFlow(readPanel())

    /** What the control panel shows. Updated after each change. */
    val panel: StateFlow<PanelState> = _panel

    /**
     * The integrator's return PendingIntent from its last start(). A PendingIntent cannot be stored,
     * so a new process falls back to a launch of [replyPackage].
     */
    @Volatile
    var returnPendingIntent: PendingIntent? = null
        private set

    /** The package that proved its identity at start(). Only this package gets replies and outcomes. */
    val replyPackage: String?
        get() = prefs.getString(KEY_REPLY_PACKAGE, null)

    /** [MANUAL], or the outcome the next payment completes with without a tap. */
    var nextOutcome: String
        get() = prefs.getString(KEY_NEXT_OUTCOME, MANUAL) ?: MANUAL
        set(value) {
            prefs.edit().putString(KEY_NEXT_OUTCOME, value).apply()
            publish()
        }

    /** Results for the next print requests, used in order. When the queue is empty, a print is OK. */
    var printResults: List<String>
        get() = prefs.getString(KEY_PRINT_RESULTS, "").orEmpty().split(',').filter { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_PRINT_RESULTS, value.joinToString(",")).apply()
            publish()
        }

    fun registerIntegrator(packageName: String, pendingIntent: PendingIntent?) {
        prefs.edit().putString(KEY_REPLY_PACKAGE, packageName).apply()
        returnPendingIntent = pendingIntent
        publish()
    }

    fun takePrintResult(): String {
        val queue = printResults
        if (queue.isEmpty()) {
            return ConnectWire.PRINT_OK
        }
        printResults = queue.drop(1)
        return queue.first()
    }

    // --- Outbox: the rows the SDK reads through OutboxProvider ---

    fun enqueuePayment(payment: PaymentState) =
        enqueue(OutboxEvent(event_id = newEventId(), payment = payment))

    fun enqueuePrint(print: PrintResult) =
        enqueue(OutboxEvent(event_id = newEventId(), print = print))

    fun enqueuePresent(present: PresentPayment) =
        enqueue(OutboxEvent(event_id = newEventId(), present = present))

    private fun enqueue(event: OutboxEvent) {
        val row = ContentValues().apply {
            put("event_id", event.event_id)
            put("envelope", ConnectCodec.encodeMessage(event))
            put("acked", 0)
        }
        db.writableDatabase.insertOrThrow(TABLE_OUTBOX, null, row)
        // The SDK observes this URI while the integrator's Activity is started, and drains on change.
        appContext.contentResolver.notifyChange(outboxUri, null)
    }

    /** The unacked envelopes, oldest first. */
    fun pendingEnvelopes(): List<String> =
        db.readableDatabase.query(TABLE_OUTBOX, arrayOf("envelope"), "acked = 0", null, null, null, "id ASC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

    fun ack(eventId: String) {
        val values = ContentValues().apply { put("acked", 1) }
        db.writableDatabase.update(TABLE_OUTBOX, values, "event_id = ?", arrayOf(eventId))
    }

    // --- Simulated payments ---

    fun insertPayment(payment: SimulatedPayment) {
        val row = ContentValues().apply {
            put("id", payment.id)
            put("amount_minor", payment.amountMinor)
            put("currency", payment.currency)
            put("client_id", payment.clientId)
            put("preset_outcome", payment.presetOutcome)
            put("status", payment.status)
        }
        db.writableDatabase.insertOrThrow(TABLE_PAYMENTS, null, row)
    }

    fun payment(id: String): SimulatedPayment? =
        db.readableDatabase.query(
            TABLE_PAYMENTS,
            arrayOf("id", "amount_minor", "currency", "client_id", "preset_outcome", "status"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            SimulatedPayment(
                id = cursor.getString(0),
                amountMinor = cursor.getLong(1),
                currency = cursor.getString(2),
                clientId = cursor.getString(3),
                presetOutcome = cursor.getString(4),
                status = cursor.getString(5),
            )
        }

    /**
     * Moves a payment from [from] to [to]. Returns false when the payment is no longer in [from],
     * so two paths cannot both decide one payment, for example a tap and the 10 second timeout.
     */
    fun transitionPayment(id: String, from: String, to: String): Boolean {
        val values = ContentValues().apply { put("status", to) }
        return db.writableDatabase.update(TABLE_PAYMENTS, values, "id = ? AND status = ?", arrayOf(id, from)) == 1
    }

    // --- Event log and reset ---

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        synchronized(log) {
            log.addFirst("$time  $message")
            while (log.size > LOG_SIZE) {
                log.removeLast()
            }
        }
        publish()
    }

    /** Clears the outbox, the payments, the settings and the registered integrator. */
    fun reset() {
        db.writableDatabase.delete(TABLE_OUTBOX, null, null)
        db.writableDatabase.delete(TABLE_PAYMENTS, null, null)
        prefs.edit().clear().apply()
        returnPendingIntent = null
        synchronized(log) { log.clear() }
        publish()
    }

    private fun publish() {
        _panel.value = readPanel()
    }

    private fun readPanel(): PanelState = PanelState(
        replyPackage = replyPackage,
        nextOutcome = nextOutcome,
        printResults = printResults,
        log = synchronized(log) { log.toList() },
    )

    private fun newEventId(): String = UUID.randomUUID().toString()

    private class Database(context: Context) : SQLiteOpenHelper(context, "connect-emulator.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_OUTBOX (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "event_id TEXT NOT NULL UNIQUE, envelope TEXT NOT NULL, acked INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE $TABLE_PAYMENTS (id TEXT PRIMARY KEY, amount_minor INTEGER NOT NULL, " +
                    "currency TEXT NOT NULL, client_id TEXT, preset_outcome TEXT, status TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        /** The next payment waits for a tap on the simulated card screen. */
        const val MANUAL = "manual"

        private const val TABLE_OUTBOX = "outbox"
        private const val TABLE_PAYMENTS = "payments"
        private const val KEY_REPLY_PACKAGE = "reply_package"
        private const val KEY_NEXT_OUTCOME = "next_outcome"
        private const val KEY_PRINT_RESULTS = "print_results"
        private const val LOG_SIZE = 50

        @Volatile
        private var instance: EmulatorStore? = null

        fun get(context: Context): EmulatorStore =
            instance ?: synchronized(this) {
                instance ?: EmulatorStore(context).also { instance = it }
            }
    }
}

/** A payment the emulator opened. [status] is open, presented, or the final outcome. */
data class SimulatedPayment(
    val id: String,
    val amountMinor: Long,
    val currency: String,
    /** The requestPayment client_id, echoed back on the outcome. Null for a simulated incoming payment. */
    val clientId: String?,
    /** The outcome to complete with without a tap, or null to wait for a tap. */
    val presetOutcome: String?,
    val status: String,
)

data class PanelState(
    val replyPackage: String?,
    val nextOutcome: String,
    val printResults: List<String>,
    val log: List<String>,
)
