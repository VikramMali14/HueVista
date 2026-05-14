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
            "\n── GOOGLE OAUTH2 ─────────────────────────────────────────────\n" +
            "  Client ID    : {}\n" +
            "  Secret       : {}\n" +
            "\n── APP ───────────────────────────────────────────────────────\n" +
            "  Base URL     : {}\n" +
            "  CORS Origins : {}\n" +
            "\n  ⚠  S3 active = {}\n" +
            "  ⚠  Local storage active = {}\n",
            // DB
            dbUrl, dbUsername, mask(dbPassword),
            // JWT
            mask(jwtSecret), jwtExpiry, refreshExpiry,
            // Claude
            mask(claudeApiKey), claudeModel, claudeEnrichmentModel,
            // S3
            s3BucketName, s3Region, mask(s3AccessKey), mask(s3SecretKey),
            // Local
            localStoragePath,
            // Replicate
            mask(replicateToken), sam2Version,
            // Google
            mask(googleClientId), mask(googleClientSecret),
            // App
            baseUrl, corsOrigins,
            // Summary flags
            !"NOT SET".equals(s3BucketName),
            "NOT SET".equals(s3BucketName)
        );
    }

    // Shows first 6 chars then *** — enough to identify the key without exposing it
    private String mask(String value) {
        if (value == null || value.isBlank() || "NOT SET".equals(value)) return "NOT SET";
        if (value.length() <= 6) return "***";
        return value.substring(0, 6) + "***";
    }
}
