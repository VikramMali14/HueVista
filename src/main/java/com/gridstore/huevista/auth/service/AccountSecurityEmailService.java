package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.util.Emails;
import com.gridstore.huevista.common.web.SiteUrls;
import com.gridstore.huevista.notification.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * "Something changed on your account" mail — the tripwire for a takeover.
 *
 * <p>These are not courtesy notes. A password change is the first thing an attacker who
 * has a session or a mailbox does, and until one of these lands the real owner has no
 * signal at all: the app revokes every session silently, so from the outside a stolen
 * account and a forgotten laptop look identical. The mail is the only part of the flow
 * that reaches somebody the attacker does not already control.
 *
 * <p>Every send is best-effort, for the same reason {@link
 * com.gridstore.huevista.billing.service.BillingEmailService} is: the credential change
 * is already committed by the caller, and a mail outage must never roll it back — that
 * would leave a user who was mid-reset with neither the old password nor the new one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSecurityEmailService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm");

    private final EmailSender emailSender;

    /** "Take your account back" has to open the website's page, not this API — see SiteUrls. */
    private final SiteUrls siteUrls;

    /**
     * Sent after a signed-in user changes their own password.
     *
     * <p>Whoever did this knew the current password, so "this wasn't me" means the old
     * password is compromised, not merely guessable — hence the instruction to reset
     * rather than to simply choose a better one.
     */
    public void sendPasswordChanged(User user) {
        String resetUrl = siteUrls.on("/sign-in/forgot");
        deliver(user, "Your HueVista password was changed",
                "Hi " + firstName(user) + ",\n\n"
                        + "Your HueVista password was changed on " + LocalDateTime.now().format(WHEN) + ".\n\n"
                        + "You've been signed out everywhere as a precaution, so any other browser or "
                        + "phone that was signed in will ask for the new password.\n\n"
                        + "If this was you, there's nothing to do.\n\n"
                        + "If it wasn't, someone knew your old password. Take the account back now:\n"
                        + resetUrl + "\n\n"
                        + "— HueVista");
    }

    /**
     * Sent after a password is set through a reset code rather than the old password.
     *
     * @param viaPhone true when the code travelled by SMS — it names the channel that was
     *                 actually trusted, which is the one the reader needs to secure
     */
    public void sendPasswordReset(User user, boolean viaPhone) {
        String resetUrl = siteUrls.on("/sign-in/forgot");
        String channel = viaPhone ? "a code sent to your phone" : "a code sent to this address";
        deliver(user, "Your HueVista password was reset",
                "Hi " + firstName(user) + ",\n\n"
                        + "Your HueVista password was reset on " + LocalDateTime.now().format(WHEN)
                        + ", using " + channel + ".\n\n"
                        + "You've been signed out everywhere as a precaution.\n\n"
                        + "If this was you, there's nothing to do.\n\n"
                        + "If it wasn't, whoever did it could read "
                        + (viaPhone ? "your text messages" : "this mailbox")
                        + " — secure that first, then reset the password again:\n"
                        + resetUrl + "\n\n"
                        + "— HueVista");
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Best-effort delivery, skipped for accounts with no reachable address.
     *
     * <p>An access-code account's stored address is synthesised from the code
     * ({@code ac-…@customers.huevista.local}) and goes nowhere — see {@link
     * Emails#isSynthetic}. Such accounts are passwordless and cannot reach these flows
     * today, so this is a guard rather than a live case; it is here because "send security
     * mail to whatever is in the email column" is exactly the assumption that breaks the
     * day a passwordless account is given a password.
     */
    private void deliver(User user, String subject, String body) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()
                || Emails.isSynthetic(user)) {
            return;
        }
        try {
            emailSender.send(user.getEmail(), subject, body);
        } catch (Exception e) {
            log.warn("Account security email failed (ignored): user={} subject=\"{}\" error={}",
                    user.getId(), subject, e.getMessage());
        }
    }

    private static String firstName(User user) {
        String name = user != null ? user.getName() : null;
        if (name == null || name.isBlank()) return "there";
        return name.strip().split("\\s+")[0];
    }
}
