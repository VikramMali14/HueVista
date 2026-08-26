package com.gridstore.huevista.auth.util;

/**
 * Single place for mobile-number normalization — the phone counterpart of {@link Emails}.
 *
 * <p>It exists for the same reason: every flow that looks up or stores a user BY NUMBER
 * (verification, SMS password reset, Firebase phone sign-in) must agree on the stored
 * form, or {@code +91 98765 43210} and {@code +919876543210} become two different
 * accounts — and phone sign-in, which finds the account by its number and nothing else,
 * would hand a returning customer a brand new empty account instead of their own.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** Keep an optional leading +, strip separators, require 8–15 digits. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("[\\s\\-()]", "");
        if (cleaned.isEmpty()) return null;
        if (!cleaned.matches("^\\+?[0-9]{8,15}$")) {
            throw new IllegalArgumentException("Enter a valid mobile number with country code, e.g. +9198…");
        }
        return cleaned;
    }

    /**
     * The number with everything but its last few digits replaced by asterisks, for
     * anything a human reads back ("we texted ********210").
     */
    public static String mask(String phone) {
        if (phone == null) return null;
        int keep = Math.min(3, phone.length());
        return "*".repeat(Math.max(0, phone.length() - keep)) + phone.substring(phone.length() - keep);
    }
}
