package com.bi2qfa.sonyconnect;

import java.security.SecureRandom;










public final class Rand {

    private static final SecureRandom RNG = new SecureRandom();

    private Rand() {
    }

    
    public static byte[] bytes(int n) {
        byte[] out = new byte[n];
        RNG.nextBytes(out);
        return out;
    }
}
