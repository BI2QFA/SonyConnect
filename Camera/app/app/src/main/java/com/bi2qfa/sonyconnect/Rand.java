package com.bi2qfa.sonyconnect;

import java.security.SecureRandom;

/**
 * 唯一的随机源：本机设备码、配对窗口的 6 位码。
 *
 * <p>原来它叫 `CryptoSuite.random` —— 那整套加解密（AES-CBC + HMAC、会话密钥
 * 派生、PBKDF2、双向认证）已经整体移除，见 devlog。随机源本身与加解密无关：
 * 设备码与配对码必须是**不可预测**的随机值，所以单独留这么一个小类。
 *
 * <p>零 Android 依赖 → 桌面 desktop-test 可编译可断言。
 */
public final class Rand {

    private static final SecureRandom RNG = new SecureRandom();

    private Rand() {
    }

    /** n 字节强随机。 */
    public static byte[] bytes(int n) {
        byte[] out = new byte[n];
        RNG.nextBytes(out);
        return out;
    }
}
