package com.gridstore.huevista.auth.util;

/**
 * Single place for email normalization. Every flow that looks up or stores a
 * user by email (register, login, OAuth2, password reset, verification) MUST
 * normalize the same way, otherwise "User@Example.com" and "user@example.com"
 * become two different accounts — or worse, one account the user can register
 * but never reset the password for.
 */
public final class Emails {

    private Emails() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
