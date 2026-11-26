package com.fermapp.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** SHA-256 hashing and random tokens, compatible with the values the Node.js server stored. */
public final class TokenHashing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHashing() {
    }

    /** {@code createHash('sha256').update(value).digest('hex')}. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** {@code randomBytes(n).toString('base64url')}. */
    public static String randomBase64Url(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
