package com.rootline.connect.emulator

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.rootline.connect.contract.ConnectWire

/**
 * The durable outbox the SDK reads payment and print outcomes from, at
 * `content://com.rootline.connect.outbox/outcomes`:
 *
 *  - `query` returns the unacked rows, oldest first, in one column with the OutboxEvent proto-JSON.
 *  - `call("ack", eventId)` marks one row as delivered.
 *
 * Only the integrator that called start() can read or ack. Any other caller gets an empty cursor.
 */
class OutboxProvider : ContentProvider() {

    private val store: EmulatorStore by lazy { EmulatorStore.get(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(ConnectWire.OUTBOX_COLUMN_ENVELOPE))
        if (uri.lastPathSegment != "outcomes" || !isRegisteredCaller()) {
            return cursor
        }
        for (envelope in store.pendingEnvelopes()) {
            cursor.addRow(arrayOf(envelope))
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == ConnectWire.OUTBOX_METHOD_ACK && arg != null && isRegisteredCaller()) {
            store.ack(arg)
        }
        return null
    }

    // The SDK also reads the outbox before its first start(). That read gets an empty cursor,
    // and the SDK reads again after start().
    private fun isRegisteredCaller(): Boolean {
        val caller = callingPackage
        return caller != null && caller == store.replyPackage
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
