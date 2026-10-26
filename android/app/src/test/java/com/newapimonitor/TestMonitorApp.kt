package com.newapimonitor

import com.newapimonitor.data.store.*
import javax.crypto.KeyGenerator

object TestSecrets {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    fun cipher() = CredentialCipher { key }
}
class TestMonitorApp : NewApiMonitorApp() {
    override fun createStore() = InstanceRepository(this, TestSecrets.cipher())
}
