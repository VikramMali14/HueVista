package com.gridstore.huevista.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mail warnings encode a production incident: the app started cleanly, then
 * every admin 2FA / password-reset send failed with "550-5.7.0 Mail relay denied".
 * These cases pin the conditions that must be visible at boot instead.
 */
class ConfigDiagnosticRunnerTest {

    /**
     * The clean/mask resolution pairing, which is a real production case rather than a
     * hypothetical: an environment kept REPLICATE_IMAGE_CLEANER_RESOLUTION=1K from an
     * older .env, so the clean carried on at 1K after the default moved to 2K while the
     * mask line beside it read 2K and looked right. Nothing in the repository showed it
     * — only the running process knew — so the boot banner is where it has to surface.
     */
    private String resolutionWarningFor(String cleanRes, String maskRes) {
        ConfigDiagnosticRunner runner = new ConfigDiagnosticRunner();
        ReflectionTestUtils.setField(runner, "imageCleanerResolution", cleanRes);
        ReflectionTestUtils.setField(runner, "maskSegmenterResolution", maskRes);
        return (String) ReflectionTestUtils.invokeMethod(runner, "cleanerResolutionWarning");
    }

    @Test
    void aCleanCoarserThanTheMaskItFeedsIsFlagged() {
        assertThat(resolutionWarningFor("1K", "2K"))
                .contains("clean is generated at 1K")
                .contains("mask is asked for 2K")
                // The remedy names the variable, because the value is almost always
                // coming from an environment nobody has looked at in a while.
                .contains("REPLICATE_IMAGE_CLEANER_RESOLUTION=2K");
    }

    @Test
    void matchingResolutionsSayNothing() {
        assertThat(resolutionWarningFor("2K", "2K")).isEmpty();
    }

    @Test
    void aCleanFinerThanTheMaskIsNotAProblem() {
        // The mask being coarser is a deliberate, cheaper choice: it is generated from a
        // canvas that already holds the edge, so it costs detail it can afford to lose.
        assertThat(resolutionWarningFor("4K", "2K")).isEmpty();
    }

    @Test
    void anUnsetOrUnknownResolutionIsNotGuessedAt() {
        // Blank means "let the model pick", and NOT SET means the property is absent.
        // Neither is comparable to 2K, and inventing a rank for them would put a warning
        // on the screen of every deployment that never set one.
        assertThat(resolutionWarningFor("NOT SET", "2K")).isEmpty();
        assertThat(resolutionWarningFor("", "2K")).isEmpty();
        assertThat(resolutionWarningFor("1K", "NOT SET")).isEmpty();
        assertThat(resolutionWarningFor("720p", "2K")).isEmpty();
    }

    private String warningsFor(String enabled, String host, String username,
                               String password, String from, String billingFrom) {
        ConfigDiagnosticRunner runner = new ConfigDiagnosticRunner();
        ReflectionTestUtils.setField(runner, "mailEnabled", enabled);
        ReflectionTestUtils.setField(runner, "mailHost", host);
        ReflectionTestUtils.setField(runner, "mailUsername", username);
        ReflectionTestUtils.setField(runner, "mailPassword", password);
        ReflectionTestUtils.setField(runner, "mailSmtpAuth", "true");
        ReflectionTestUtils.setField(runner, "mailFrom", from);
        ReflectionTestUtils.setField(runner, "mailBillingFrom", billingFrom);
        return (String) ReflectionTestUtils.invokeMethod(runner, "mailWarnings");
    }

    @Test
    void mailDisabledSaysCodesAreOnlyLogged() {
        assertThat(warningsFor("false", "", "", "", "no-reply@huevista.org", "payments@huevista.org"))
                .contains("Mail disabled")
                .contains("only written to this log");
    }

    @Test
    void enabledWithoutHostIsCalledOutAsSilentFallback() {
        // The dangerous case: operators set MAIL_ENABLED=true, believe mail is on,
        // and no JavaMailSender bean is ever created.
        assertThat(warningsFor("true", "", "u@huevista.org", "pw", "no-reply@huevista.org", "payments@huevista.org"))
                .contains("MAIL_HOST is empty")
                .contains("[DEV EMAIL]");
    }

    @Test
    void missingCredentialsAreFlagged() {
        assertThat(warningsFor("true", "smtp.gmail.com", "", "", "no-reply@huevista.org", "payments@huevista.org"))
                .contains("MAIL_USERNAME / MAIL_PASSWORD is empty");
    }

    @Test
    void workspaceRelayEndpointIsFlagged() {
        // The exact host behind the outage.
        assertThat(warningsFor("true", "smtp-relay.gmail.com", "vikram@gmail.com", "pw",
                "no-reply@huevista.org", "payments@huevista.org"))
                .contains("Workspace-only")
                .contains("550-5.7.0 Mail relay denied");
    }

    @Test
    void senderOffTheAuthenticatedDomainIsFlaggedForEverySender() {
        String warnings = warningsFor("true", "smtp.gmail.com", "someone@gmail.com", "pw",
                "no-reply@huevista.org", "payments@huevista.org");
        assertThat(warnings)
                .contains("no-reply@huevista.org is not on the authenticated domain (gmail.com)")
                .contains("payments@huevista.org is not on the authenticated domain (gmail.com)");
    }

    @Test
    void matchingDomainOnRealHostIsClean() {
        assertThat(warningsFor("true", "email-smtp.ap-south-1.amazonaws.com", "postmaster@huevista.org", "pw",
                "no-reply@huevista.org", "payments@huevista.org"))
                .isEqualTo("  ✓  Mail delivery configured\n");
    }

    @Test
    void bareSmtpLoginNameSkipsTheDomainCheck() {
        // SES / Postmark hand out opaque SMTP usernames that are not addresses;
        // there is no domain to compare, so the sender check must stay quiet.
        assertThat(warningsFor("true", "email-smtp.ap-south-1.amazonaws.com", "AKIAIOSFODNN7EXAMPLE", "pw",
                "no-reply@huevista.org", "payments@huevista.org"))
                .isEqualTo("  ✓  Mail delivery configured\n");
    }
}
