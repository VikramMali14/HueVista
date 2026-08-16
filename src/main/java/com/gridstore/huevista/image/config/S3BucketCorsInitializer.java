package com.gridstore.huevista.image.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

/**
 * Makes sure the image bucket will answer the browser's CORS preflight, once, at
 * startup.
 *
 * The frontend loads every image it recolours with {@code crossOrigin="anonymous"},
 * because a canvas it cannot read back is no use to a colour visualiser. That turns
 * each image load into a CORS request, and S3 answers those without
 * {@code Access-Control-Allow-Origin} unless the bucket itself carries a rule naming
 * the site — presigning the URL does not change that. The result was a blocked load
 * and a blank room, most visibly on the public share page, where a link forwarded
 * over WhatsApp opened onto a photo that never arrived.
 *
 * Doing it here rather than in a runbook is a deliberate choice: the bucket name and
 * the site origins are both already configuration this application reads, so a new
 * environment gets a working bucket by being deployed rather than by someone
 * remembering a console step. The frontend keeps a same-origin fallback for the case
 * this cannot fix (see its {@code /api/media} route), so a missing rule degrades to
 * proxied bytes rather than to a broken page.
 *
 * Nothing here is allowed to be load-bearing:
 *
 *  - It runs after the context is up, so a slow or unreachable S3 delays no request.
 *  - It writes only when the existing configuration does not already allow the
 *    origins, so a rule an operator added by hand survives every restart.
 *  - Every failure is caught. {@code s3:PutBucketCors} is a permission an IAM role
 *    can reasonably lack, and an image bucket that needs one console click is not a
 *    reason to refuse to start — so the failure is logged with the exact payload to
 *    apply instead.
 *
 * Set {@code app.s3.configure-cors=false} to leave the bucket alone entirely.
 */
@Slf4j
@Component
@Conditional(S3EnabledCondition.class)
@RequiredArgsConstructor
public class S3BucketCorsInitializer implements ApplicationRunner {

    private final S3Client s3Client;

    @Value("${app.s3.bucket-name}")
    private String bucketName;

    /**
     * The same list the API's own CORS filter uses — the sites allowed to call the
     * API are the sites that display its images.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Value("${app.s3.configure-cors:true}")
    private boolean configureCors;

    @Override
    public void run(ApplicationArguments args) {
        if (!configureCors) {
            log.info("S3 bucket CORS check disabled (app.s3.configure-cors=false)");
            return;
        }
        List<String> origins = S3CorsPolicy.origins(allowedOrigins);
        if (origins.isEmpty()) {
            log.warn("No app.cors.allowed-origins configured — leaving bucket {} CORS untouched. "
                    + "Browser image loads will be blocked unless the bucket already has a rule.", bucketName);
            return;
        }

        try {
            List<CORSRule> existing = readRules();
            if (S3CorsPolicy.covers(existing, origins)) {
                log.info("S3 bucket {} already allows browser reads from {}", bucketName, origins);
                return;
            }
            s3Client.putBucketCors(r -> r
                    .bucket(bucketName)
                    .corsConfiguration(CORSConfiguration.builder()
                            .corsRules(S3CorsPolicy.merge(existing, origins))
                            .build()));
            log.info("Installed CORS rule '{}' on S3 bucket {} for {}",
                    S3CorsPolicy.RULE_ID, bucketName, origins);
        } catch (Exception e) {
            // The frontend proxy covers this, so it is a warning, not an error — but
            // print what to run, because the proxy costs bandwidth this doesn't.
            log.warn("Could not configure CORS on S3 bucket {} ({}). Images will be served through "
                            + "the frontend's /api/media fallback until this is applied by hand:\n"
                            + "  aws s3api put-bucket-cors --bucket {} --cors-configuration '{}'",
                    bucketName, e.getMessage(), bucketName, S3CorsPolicy.asJson(origins));
        }
    }

    /** The bucket's current rules; empty when it has never had a CORS configuration. */
    private List<CORSRule> readRules() {
        try {
            return s3Client.getBucketCors(r -> r.bucket(bucketName)).corsRules();
        } catch (S3Exception e) {
            // The only "error" that means "nothing configured yet" rather than a problem.
            if (e.awsErrorDetails() != null
                    && "NoSuchCORSConfiguration".equals(e.awsErrorDetails().errorCode())) {
                return List.of();
            }
            throw e;
        }
    }
}
