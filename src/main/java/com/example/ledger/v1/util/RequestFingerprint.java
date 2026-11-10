package com.example.ledger.v1.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestFingerprint {
    private RequestFingerprint() {
    }

    public static byte[] sha256(String canonicalRequest) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
