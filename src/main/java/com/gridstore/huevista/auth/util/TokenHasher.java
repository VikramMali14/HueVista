package com.gridstore.huevista.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes refresh tokens before they are stored, so a leaked database (or backup)
 * never yields usable session credentials. SHA-256 without a salt is sufficient
 * here — the input is a random UUID (~122 bits of entropy), not a human password,
 * so brute-forcing the preimage is infeasible and a plain hash keeps the lookup
 * a simple indexed equality query.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec; this can never happen on a compliant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
