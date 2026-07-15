package com.gridstore.huevista.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Pluggable e-mail sender. When {@code app.mail.enabled=true} AND a JavaMailSender
 * is configured (i.e. {@code spring.mail.host} is set), it sends a real message;
 * otherwise it logs the message to the server console. This lets the verification
 * flow be built and tested in dev with zero external infrastructure, and flipped
 * to real SMTP in prod purely via configuration.
 */
@Component
@Slf4j
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;

    @Value("${app.mail.from:no-reply@huevista.app}")
    private String from;

    public EmailSender(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    /**
     * True when a real message will actually leave the building (enabled AND SMTP
     * configured). Gates features that must not silently degrade — e.g. admin login
     * 2FA only applies when the code can genuinely reach the admin's inbox.
     */
    public boolean isDeliveryEnabled() {
        return enabled && mailSenderProvider.getIfAvailable() != null;
    }

    public void send(String to, String subject, String body) {
        send(from, to, subject, body);
    }

    /**
     * Send from a specific address (e.g. the billing sender for payment receipts).
     * The SMTP account must be allowed to send as {@code fromAddress}, or providers
     * will rewrite/reject it — keep all senders on the same authenticated domain.
     */
    public void send(String fromAddress, String to, String subject, String body) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        String effectiveFrom = (fromAddress == null || fromAddress.isBlank()) ? from : fromAddress;
        if (enabled && sender != null) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(effectiveFrom);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            log.info("Email sent to {} (from {})", to, effectiveFrom);
        } else {
            // DEV / unconfigured: surface the message so the code is testable.
            log.warn("[DEV EMAIL] from={} to={} | subject=\"{}\" | {}", effectiveFrom, to, subject, body);
        }
    }
}
