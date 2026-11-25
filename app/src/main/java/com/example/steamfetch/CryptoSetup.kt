package com.example.steamfetch

import java.security.Provider
import java.security.Security

/**
 * Android's built-in "BC" security provider is a stripped-down copy of Bouncy Castle without
 * algorithms such as SHA-1, and JavaSteam asks for them by provider name "BC".
 * Spongy Castle is a complete copy under a different package name, so we expose it as "BC".
 */
class BcProvider : Provider("BC", 1.0, "Spongy Castle exposed under the name BC") {
    init {
        val spongy = org.spongycastle.jce.provider.BouncyCastleProvider()
        for ((k, v) in spongy.entries) {
            val key = k.toString()
            if (!key.startsWith("Provider.id")) put(k, v)
        }
    }
}

object CryptoSetup {
    fun install() {
        Security.removeProvider("BC")
        Security.addProvider(BcProvider())
    }
}
