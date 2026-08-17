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

    /** Domain of the placeholder addresses stood up for accounts with no reachable e-mail. */
    public static final String SYNTHETIC_DOMAIN = "@customers.huevista.local";

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * The placeholder address for an account keyed to nothing but an access code —
     * {@code ac-7kq2xr9m@customers.huevista.local}. Unique because the code is.
     */
    public static String syntheticFor(String accessCode) {
        return "ac-" + accessCode.trim().toLowerCase() + SYNTHETIC_DOMAIN;
    }

    /**
     * The e-mail address it is honest to SHOW for a user — null when there isn't one.
     *
     * An account created by redeeming a shop access code may be passwordless with no real
     * address; the stored one is then synthesised from the code purely so the row has a
     * unique key. Showing it anywhere — the account panel, the shop's customer list —
     * presents a machine identifier as a contact address: the customer reads it as
     * "someone's account", and the shop reads it as somewhere they could write. Neither is
     * true, so it is withheld everywhere the user-facing API answers.
     *
     * The admin console deliberately does not use this: support needs to see the real
     * stored value to reconcile an account against a code.
     */
    public static String publicEmailOf(com.gridstore.huevista.auth.model.User user) {
        if (user == null) return null;
        return isSynthetic(user) ? null : user.getEmail();
    }

    /**
     * True when the account's stored address is a placeholder, not something reachable.
     *
     * <p>Decided by the ADDRESS, not by the provider. A kiosk walk-in who gives their
     * e-mail at checkout gets an {@code ACCESS_CODE} account holding that real address —
     * it is how their receipt reaches them and how they get back in — and keying this on
     * the provider hid the customer's own e-mail from them everywhere the API answers.
     */
    public static boolean isSynthetic(com.gridstore.huevista.auth.model.User user) {
        return user != null && user.getEmail() != null
                && user.getEmail().endsWith(SYNTHETIC_DOMAIN);
    }
}
