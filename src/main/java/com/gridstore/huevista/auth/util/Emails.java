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

    /**
     * The e-mail address it is honest to SHOW for a user — null when there isn't one.
     *
     * An account created by redeeming a shop access code is passwordless and has no real
     * address; the stored one is synthesised from the code purely so the row has a unique
     * key ({@code ac-7kq2xr9m@customers.huevista.local}). Showing it anywhere — the
     * account panel, the shop's customer list — presents a machine identifier as a
     * contact address: the customer reads it as "someone's account", and the shop reads
     * it as somewhere they could write. Neither is true, so it is withheld everywhere the
     * user-facing API answers.
     *
     * The admin console deliberately does not use this: support needs to see the real
     * stored value to reconcile an account against a code.
     */
    public static String publicEmailOf(com.gridstore.huevista.auth.model.User user) {
        if (user == null) return null;
        return isSynthetic(user) ? null : user.getEmail();
    }

    /** True when the account's stored address is a placeholder, not something reachable. */
    public static boolean isSynthetic(com.gridstore.huevista.auth.model.User user) {
        return user != null
                && user.getProvider() == com.gridstore.huevista.auth.model.AuthProvider.ACCESS_CODE;
    }
}
