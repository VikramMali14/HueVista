package com.gridstore.huevista.notification;

/**
 * Texts a one-time code to a mobile number.
 *
 * <h2>Why there is only one method, and why it takes a code rather than a message</h2>
 * Because Indian SMS regulation leaves no room for anything else. Under TRAI's DLT
 * regime every message body must be a template registered in advance, and the operator
 * drops anything that does not match a registered template character for character. A
 * {@code send(phone, String freeText)} signature would therefore be a lie: the caller
 * cannot choose the words, only supply the values that go in the blanks.
 *
 * <p>So this takes the two things that actually vary — the code and how long it lasts —
 * and each implementation decides how they reach the handset. That is also the whole of
 * what this application ever sends by SMS: a verification code, a password-reset code, a
 * sign-in code. One registered template covers all three.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link LoggingSmsSender} — writes the code to the server log. The default, and
 *       the only one that works with no provider account.</li>
 *   <li>{@link Msg91SmsSender} — real delivery, once a DLT registration and an MSG91
 *       template exist. Chosen automatically when configured; see {@link SmsConfig}.</li>
 * </ul>
 *
 * <h2>A send must never throw</h2>
 * A failed notification must not surface as a 500 on signup or verification. Callers
 * that need to know whether delivery is even possible should ask {@link #deliversForReal()}
 * rather than inferring it from a send that did not throw.
 */
public interface SmsSender {

    /**
     * Text {@code code} to {@code phoneNumber}, best effort.
     *
     * @param phoneNumber  E.164-ish, with country code (a leading {@code +} is optional)
     * @param code         the one-time code
     * @param validMinutes how long it stays valid, for the template's second blank
     */
    void sendOtp(String phoneNumber, String code, int validMinutes);

    /**
     * Whether a message sent through this sender actually reaches a handset.
     *
     * <p>This is the question {@code app.sms.enabled} was standing in for and answering
     * wrongly: it gated retailer verification behind a channel that delivered nothing, so
     * turning it on locked every retailer out waiting for a code that only ever went to
     * the log. Asking the sender itself cannot drift from reality.
     */
    default boolean deliversForReal() {
        return false;
    }
}
