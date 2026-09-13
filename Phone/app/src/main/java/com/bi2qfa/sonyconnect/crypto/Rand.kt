package com.bi2qfa.sonyconnect.crypto

import java.security.SecureRandom








object Rand {

    private val RNG = SecureRandom()

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        RNG.nextBytes(out)
        return out
    }
}
