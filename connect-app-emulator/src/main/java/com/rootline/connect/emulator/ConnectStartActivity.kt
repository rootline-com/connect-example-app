package com.rootline.connect.emulator

import android.app.Activity
import android.os.Bundle
import com.rootline.connect.contract.ConnectCodec
import com.rootline.connect.contract.ConnectWire
import com.rootline.connect.contract.v1.ConnectionState
import com.rootline.connect.contract.v1.RuntimeStarted
import com.rootline.connect.contract.v1.StartRequest

/**
 * Answers the SDK's start(). There is no runtime to boot and no NATS connection to make, so the
 * emulator reports both as up at once, then steps aside.
 *
 * The caller must prove the package it names as reply package: the return PendingIntent's creator,
 * set by Android, must be that package. This is the same proof the real connect-app asks for.
 */
class ConnectStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = EmulatorStore.get(this)

        val request = intent.getStringExtra(ConnectWire.EXTRA_PAYLOAD)
            ?.let { ConnectCodec.decode(it, StartRequest::class.java) }
        val replyPackage = request?.reply_package?.ifEmpty { null }
        val returnPendingIntent = Integrator.returnPendingIntent(intent)
        if (replyPackage == null || returnPendingIntent?.creatorPackage != replyPackage) {
            store.log("Ignored start(): the caller did not prove its package")
            finish()
            return
        }

        store.registerIntegrator(replyPackage, returnPendingIntent)
        Integrator.sendStatus(this, replyPackage, ConnectWire.ACTION_STARTED, RuntimeStarted(success = true))
        Integrator.sendStatus(this, replyPackage, ConnectWire.ACTION_CONNECTION_STATE, ConnectionState(connected = true))
        store.log("start() from $replyPackage: runtime started, connected")
        finish()
    }
}
