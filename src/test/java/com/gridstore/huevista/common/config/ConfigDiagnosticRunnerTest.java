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
