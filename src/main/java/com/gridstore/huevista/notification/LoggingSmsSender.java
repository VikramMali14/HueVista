package com.gridstore.huevista.notification;

import lombok.extern.slf4j.Slf4j;

/**
 * Writes the code to the server log instead of sending it.
 *
 * <p>The default whenever no SMS provider is configured, which keeps every OTP flow
 * usable in development without a paid account and without a DLT registration. It says
 * plainly that nothing was delivered — {@link #deliversForReal()} returns false — so the
 * retailer verification gate does not demand a code that will never arrive.
 */
@Slf4j
public class LoggingSmsSender implements SmsSender {

    @Override
    public void sendOtp(String phoneNumber, String code, int validMinutes) {
        log.warn("[DEV SMS] to={} | HueVista code: {} (valid {} min) — NOT DELIVERED, no SMS provider configured",
                phoneNumber, code, validMinutes);
    }
}
