package com.gridstore.huevista.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the SMS sender from configuration.
 *
 * <p>Deliberately a factory rather than three {@code @Component}s: with all of them
 * annotated, every injection point would be ambiguous and the choice would live in a
 * {@code @Primary} or {@code @Profile} somewhere far from here. This makes the rule
 * readable in one place.
 *
 * <p>The order is Twilio, then MSG91, then the log. Nothing clever — configure one and
 * it is used; configure neither and the codes go to the server log, which is what keeps
 * every OTP flow usable in development with no provider account and no DLT registration.
 *
 * <p>Both real providers need a DLT registration to deliver anything to an Indian
 * number. That is a TRAI rule about the phone network and neither provider can waive it;
 * see {@link TwilioSmsSender} and {@link Msg91SmsSender}.
 */
@Configuration
@Slf4j
public class SmsConfig {

    @Bean
    public SmsSender smsSender(
            // --- Twilio ---
            @Value("${app.sms.twilio.account-sid:}") String twilioAccountSid,
            @Value("${app.sms.twilio.auth-token:}") String twilioAuthToken,
            @Value("${app.sms.twilio.sender:}") String twilioSender,
            @Value("${app.sms.twilio.messaging-service-sid:}") String twilioMessagingServiceSid,
            @Value("${app.sms.twilio.dlt-entity-id:}") String twilioDltEntityId,
            @Value("${app.sms.twilio.dlt-template-id:}") String twilioDltTemplateId,
            @Value("${app.sms.twilio.message-template:}") String twilioMessageTemplate,
            // --- MSG91 ---
            @Value("${app.sms.msg91.auth-key:}") String msg91AuthKey,
            @Value("${app.sms.msg91.template-id:}") String msg91TemplateId,
            @Value("${app.sms.msg91.otp-variable:otp}") String msg91OtpVariable,
            @Value("${app.sms.msg91.validity-variable:mins}") String msg91ValidityVariable,
            @Value("${app.sms.msg91.sender-id:}") String msg91SenderId) {

        if (!twilioAccountSid.isBlank() && !twilioAuthToken.isBlank()) {
            if (twilioMessageTemplate.isBlank()) {
                // The words are not ours to choose under DLT, so there is no sane default
                // to fall back on: an unregistered body is dropped by the operator with no
                // error anywhere. Refuse to pretend this is configured.
                log.error("Twilio is configured but app.sms.twilio.message-template is empty — "
                        + "it must match your approved DLT template exactly. SMS stays in the log.");
                return new LoggingSmsSender();
            }
            if (twilioMessagingServiceSid.isBlank() && twilioSender.isBlank()) {
                log.error("Twilio is configured but neither a messaging service nor a sender is set — "
                        + "there is nothing to send from. SMS stays in the log.");
                return new LoggingSmsSender();
            }
            if (twilioDltEntityId.isBlank() || twilioDltTemplateId.isBlank()) {
                // Not fatal — Twilio accepts the send and non-Indian numbers are fine —
                // but every Indian operator will drop it, silently. Say so loudly now
                // rather than leaving somebody to discover it one undelivered code at a time.
                log.warn("Twilio has no DLT entity/template id — messages to INDIAN numbers will be "
                        + "dropped by the operator with no error. Set both once your DLT registration "
                        + "is approved.");
            }
            log.info("SMS delivery via Twilio ({})",
                    twilioMessagingServiceSid.isBlank() ? "from " + twilioSender
                            : "messaging service " + twilioMessagingServiceSid);
            return new TwilioSmsSender(twilioAccountSid.trim(), twilioAuthToken.trim(),
                    twilioSender, twilioMessagingServiceSid, twilioDltEntityId, twilioDltTemplateId,
                    twilioMessageTemplate);
        }

        if (!msg91AuthKey.isBlank() && !msg91TemplateId.isBlank()) {
            log.info("SMS delivery via MSG91 (template {}, variables {}/{}{})",
                    msg91TemplateId, msg91OtpVariable, msg91ValidityVariable,
                    msg91SenderId.isBlank() ? "" : ", sender " + msg91SenderId);
            return new Msg91SmsSender(msg91AuthKey.trim(), msg91TemplateId.trim(),
                    msg91OtpVariable.trim(), msg91ValidityVariable.trim(), msg91SenderId);
        }

        // Say which half is missing. "SMS is off" with a key set and no template is the
        // kind of message that costs an afternoon.
        if (!msg91AuthKey.isBlank() || !msg91TemplateId.isBlank()) {
            log.warn("MSG91 is half-configured (auth key {}, template id {}) — SMS stays in the log. "
                            + "Both are required.",
                    msg91AuthKey.isBlank() ? "MISSING" : "set", msg91TemplateId.isBlank() ? "MISSING" : "set");
        } else if (!twilioAccountSid.isBlank() || !twilioAuthToken.isBlank()) {
            log.warn("Twilio is half-configured (account sid {}, auth token {}) — SMS stays in the log. "
                            + "Both are required.",
                    twilioAccountSid.isBlank() ? "MISSING" : "set", twilioAuthToken.isBlank() ? "MISSING" : "set");
        } else {
            log.info("No SMS provider configured — one-time codes are written to this log only.");
        }
        return new LoggingSmsSender();
    }
}
