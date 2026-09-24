package com.bi2qfa.sonyconnect.crypto

import java.security.SecureRandom

/**
 * 唯一的随机源：本机 8 字节设备码。
 *
 * <p>原来它是 `KeyDerivation.random` —— 整套加解密（AES-CBC + HMAC、会话密钥
 * 派生、PBKDF2、双向认证）已经整体移除。随机源本身与加解密无关：设备码必须是
 * **不可预测**的随机值，所以单独留这么一个小对象。相机端有对称的 `Rand.java`。
 */
object Rand {

    private val RNG = SecureRandom()

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        RNG.nextBytes(out)
        return out
    }
}
