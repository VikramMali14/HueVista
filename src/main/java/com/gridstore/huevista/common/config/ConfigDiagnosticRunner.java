package com.gridstore.huevista.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ConfigDiagnosticRunner implements ApplicationRunner {

    // --- Database ---
    @Value("${spring.datasource.url:NOT SET}")
    private String dbUrl;

    @Value("${spring.datasource.username:NOT SET}")
    private String dbUsername;

    @Value("${spring.datasource.password:NOT SET}")
    private String dbPassword;

    // --- JWT ---
    @Value("${app.jwt.secret:NOT SET}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:NOT SET}")
    private String jwtExpiry;

    @Value("${app.refresh-token.expiration-ms:NOT SET}")
    private String refreshExpiry;

    // --- Claude ---
    @Value("${app.claude.api-key:NOT SET}")
    private String claudeApiKey;

    @Value("${app.claude.model:NOT SET}")
    private String claudeModel;

    @Value("${app.claude.enrichment-model:NOT SET}")
    private String claudeEnrichmentModel;

    // --- S3 ---
    @Value("${app.s3.bucket-name:NOT SET}")
    private String s3BucketName;

    @Value("${app.s3.region:NOT SET}")
    private String s3Region;

    @Value("${app.s3.access-key:NOT SET}")
    private String s3AccessKey;

    @Value("${app.s3.secret-key:NOT SET}")
    private String s3SecretKey;

    // --- Replicate ---
    @Value("${replicate.api-token:NOT SET}")
    private String replicateToken;

    @Value("${replicate.sam2.model-version:NOT SET}")
    private String sam2Version;

    // Auto mask generation (ReplicateMaskSegmenter). The model line is the
    // fastest way to confirm which model will actually run — a deployment
    // env var (REPLICATE_NANO_BANANA_MODEL) silently overrides the
    // application.properties default, and this prints the resolved winner.
    @Value("${replicate.nano-banana.enabled:false}")
    private String maskSegmenterEnabled;

    @Value("${replicate.nano-banana.model:NOT SET}")
    private String maskSegmenterModel;

    @Value("${replicate.nano-banana.resolution:NOT SET}")
    private String maskSegmenterResolution;

    // Image cleaner (pre-mask declutter + repaint)
    @Value("${replicate.image-cleaner.enabled:false}")
    private String imageCleanerEnabled;

    @Value("${replicate.image-cleaner.model:NOT SET}")
    private String imageCleanerModel;

    // --- Storage ---
    @Value("${app.upload.storage-path:NOT SET}")
    private String localStoragePath;

    // --- CORS ---
    @Value("${app.cors.allowed-origins:NOT SET}")
    private String corsOrigins;

    // --- App ---
    @Value("${app.base-url:NOT SET}")
    private String baseUrl;

    // --- Google OAuth2 ---
    @Value("${spring.security.oauth2.client.registration.google.client-id:NOT SET}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret:NOT SET}")
    private String googleClientSecret;

    // --- Mail / SMTP ---
    // Mail is the one subsystem that fails *after* a clean startup: a bad relay
    // only announces itself when the first 2FA / reset / verification code is
    // sent, by which time someone is already locked out. Print the resolved
    // settings here so a misconfiguration is visible at boot, not at 3am.
    @Value("${app.mail.enabled:false}")
    private String mailEnabled;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.port:}")
    private String mailPort;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${spring.mail.properties.mail.smtp.auth:}")
    private String mailSmtpAuth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:}")
    private String mailStartTls;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @Value("${app.mail.billing-from:}")
    private String mailBillingFrom;

    @Override
    public void run(ApplicationArguments args) {
        log.info("\n\n" +
            "╔══════════════════════════════════════════════════════════╗\n" +
            "║           HueVista Config Diagnostic                    ║\n" +
            "╚══════════════════════════════════════════════════════════╝\n" +
            "\n── DATABASE ─────────────────────────────────────────────────\n" +
            "  URL      : {}\n" +
            "  Username : {}\n" +
            "  Password : {}\n" +
            "\n── JWT ──────────────────────────────────────────────────────\n" +
            "  Secret           : {}\n" +
            "  Access Expiry    : {} ms\n" +
            "  Refresh Expiry   : {} ms\n" +
            "\n── CLAUDE API ───────────────────────────────────────────────\n" +
            "  API Key          : {}\n" +
            "  Vision Model     : {}\n" +
            "  Enrichment Model : {}\n" +
            "\n── AWS S3 (inactive if bucket = NOT SET) ────────────────────\n" +
            "  Bucket Name  : {}\n" +
            "  Region       : {}\n" +
            "  Access Key   : {}\n" +
            "  Secret Key   : {}\n" +
            "\n── LOCAL STORAGE ────────────────────────────────────────────\n" +
            "  Upload Path  : {}\n" +
            "\n── REPLICATE / SAM 2 ────────────────────────────────────────\n" +
            "  API Token    : {}\n" +
            "  SAM2 Version : {}\n" +
            "\n── AUTO SEGMENTATION (mask + cleaner) ───────────────────────\n" +
            "  Mask Enabled    : {}\n" +
            "  Mask Model      : {}\n" +
            "  Mask Resolution : {}\n" +
            "  Cleaner Enabled : {}\n" +
            "  Cleaner Model   : {}\n" +
            "\n── GOOGLE OAUTH2 ─────────────────────────────────────────────\n" +
            "  Client ID    : {}\n" +
            "  Secret       : {}\n" +
            "\n── MAIL / SMTP ───────────────────────────────────────────────\n" +
            "  Enabled      : {}\n" +
            "  Host         : {}\n" +
            "  Port         : {}\n" +
            "  Username     : {}\n" +
            "  Password     : {}\n" +
            "  SMTP Auth    : {}\n" +
            "  STARTTLS     : {}\n" +
            "  From         : {}\n" +
            "  Billing From : {}\n" +
            "  Delivering   : {}\n" +
            "\n── APP ───────────────────────────────────────────────────────\n" +
            "  Base URL     : {}\n" +
            "  CORS Origins : {}\n" +
            "\n  ⚠  S3 active = {}\n" +
            "  ⚠  Local storage active = {}\n" +
            "{}",
            // DB — only presence, never any part of the password (logs are often
            // shipped to external aggregators).
            dbUrl, dbUsername, isSet(dbPassword),
            // JWT — the secret is the crown jewels; never print any part of it.
            isSet(jwtSecret), jwtExpiry, refreshExpiry,
            // Claude
            mask(claudeApiKey), claudeModel, claudeEnrichmentModel,
            // S3 — blank keys are the normal production case, not a fault: S3Config
            // falls through to the default AWS chain (IAM role → AWS_* env vars).
            s3BucketName, s3Region, s3Key(mask(s3AccessKey)), s3Key(isSet(s3SecretKey)),
            // Local
            localStoragePath,
            // Replicate
            mask(replicateToken), blank(sam2Version),
            // Auto segmentation — plain values, nothing secret here
            maskSegmenterEnabled, maskSegmenterModel, maskSegmenterResolution,
            imageCleanerEnabled, imageCleanerModel,
            // Google
            mask(googleClientId), isSet(googleClientSecret),
            // Mail — username is an account identity, so mask rather than print;
            // the password never appears, only SET / NOT SET.
            mailEnabled, blank(mailHost), blank(mailPort), mask(mailUsername), isSet(mailPassword),
            blank(mailSmtpAuth), blank(mailStartTls), blank(mailFrom), blank(mailBillingFrom),
            deliveringRealMail() ? "yes — real SMTP" : "no — codes only logged as [DEV EMAIL]",
            // App
            baseUrl, corsOrigins,
            // Summary flags
            !"NOT SET".equals(s3BucketName),
            "NOT SET".equals(s3BucketName),
            mailWarnings()
        );
    }

    // True when a message will genuinely leave the building. Mirrors
    // EmailSender.isDeliveryEnabled(): the JavaMailSender bean only exists when
    // spring.mail.host is set, so an enabled-but-hostless config degrades to
    // logging the code instead of sending it.
    private boolean deliveringRealMail() {
        return "true".equalsIgnoreCase(mailEnabled) && !isBlank(mailHost);
    }

    /**
     * Flags the mail misconfigurations that produce a clean startup followed by a
     * 550 at send time. Each branch corresponds to a failure we have actually hit
     * in a deployed environment.
     */
    private String mailWarnings() {
        if (!"true".equalsIgnoreCase(mailEnabled)) {
            return "  ⚠  Mail disabled — verification, password-reset and admin 2FA codes are\n"
                 + "     only written to this log. Set MAIL_ENABLED=true to deliver them.\n";
        }
        StringBuilder sb = new StringBuilder();
        if (isBlank(mailHost)) {
            return "  ⚠  MAIL_ENABLED=true but MAIL_HOST is empty — no JavaMailSender is created,\n"
                 + "     so codes silently fall back to the [DEV EMAIL] log line.\n";
        }
        if ("true".equalsIgnoreCase(mailSmtpAuth) && (isBlank(mailUsername) || isBlank(mailPassword))) {
            sb.append("  ⚠  smtp.auth=true but MAIL_USERNAME / MAIL_PASSWORD is empty — the relay\n")
              .append("     will refuse every message.\n");
        }
        if (mailHost.contains("smtp-relay.gmail.com")) {
            sb.append("  ⚠  smtp-relay.gmail.com is Google Workspace-only. It requires a MANAGED\n")
              .append("     Workspace account AND this host's public egress IP registered under\n")
              .append("     Admin console → Apps → Google Workspace → Gmail → Routing → SMTP relay\n")
              .append("     service. Personal @gmail.com / unmanaged accounts always get\n")
              .append("     '550-5.7.0 Mail relay denied'. Use smtp.gmail.com with an app password,\n")
              .append("     or a transactional provider (SES / Resend / Brevo), instead.\n");
        }
        // The From address must be one the SMTP account is authorised to send as.
        // A mismatch is what turns into "Invalid credentials for relay for one of
        // the domains in: ..." — the relay authenticates you, then finds you are
        // claiming a domain it has no record of.
        String authDomain = domainOf(mailUsername);
        if (authDomain != null) {
            for (String sender : new String[]{ mailFrom, mailBillingFrom }) {
                String senderDomain = domainOf(sender);
                if (senderDomain != null && !senderDomain.equalsIgnoreCase(authDomain)) {
                    sb.append("  ⚠  Sender ").append(sender).append(" is not on the authenticated domain (")
                      .append(authDomain).append(") —\n")
                      .append("     the provider will reject or rewrite a From it cannot authenticate.\n");
                }
            }
        }
        return sb.isEmpty() ? "  ✓  Mail delivery configured\n" : sb.toString();
    }

    // Returns the domain part of an e-mail address, or null when the value is not
    // an address (a bare SMTP login name, or blank).
    private String domainOf(String address) {
        if (isBlank(address)) return null;
        int at = address.lastIndexOf('@');
        return (at < 0 || at == address.length() - 1) ? null : address.substring(at + 1);
    }

    // Shows first 6 chars then *** — enough to identify the key without exposing it
    private String mask(String value) {
        if (value == null || value.isBlank() || "NOT SET".equals(value)) return "NOT SET";
        if (value.length() <= 6) return "***";
        return value.substring(0, 6) + "***";
    }

    // For values where even a prefix is too much (passwords): only SET / NOT SET.
    private String isSet(String value) {
        return isBlank(value) ? "NOT SET" : "SET";
    }

    // Properties declared as ${VAR:} resolve to an empty string rather than falling
    // back to the @Value default, which prints as a confusing blank line. Render
    // those the same way as a genuinely absent value.
    private String blank(String value) {
        return isBlank(value) ? "NOT SET" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "NOT SET".equals(value);
    }

    // An unset app.s3.access-key/secret-key is deliberate in production — S3Config
    // hands off to DefaultCredentialsProvider. Say so, so a bare "NOT SET" next to
    // a working S3 client does not read as a broken deployment.
    private String s3Key(String rendered) {
        return "NOT SET".equals(rendered) ? "NOT SET (default AWS chain: IAM role / AWS_* env)" : rendered;
    }
}
