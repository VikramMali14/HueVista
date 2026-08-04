package com.gridstore.huevista.lead.service;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AdminUserResponse;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.hierarchy.service.HierarchyService;
import com.gridstore.huevista.lead.dto.ShopLeadRequest;
import com.gridstore.huevista.lead.dto.ShopLeadResponse;
import com.gridstore.huevista.lead.dto.ShopRequestStatusResponse;
import com.gridstore.huevista.lead.model.ShopLead;
import com.gridstore.huevista.lead.repository.ShopLeadRepository;
import com.gridstore.huevista.notification.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shop-account requests, end to end.
 *
 * <p>The flow is: a shop owner fills the public form (their details plus a password
 * they type twice), proves the mailbox with a 6-digit code, and the request lands in
 * the admin queue carrying everything an account needs. An admin then presses one
 * button — the only decision left being which distributor the shop belongs under —
 * and if nobody presses it within {@link #AUTO_APPROVE_AFTER}, the request
 * provisions itself under the house distributor. Either way the shop gets a free
 * account and an email saying so.
 *
 * <p>Three things this deliberately does not do:
 * <ul>
 *   <li><b>Ask which plan they want.</b> Every shop is created on the free tier. A
 *       paid plan is reached by buying one and by nothing else — there is no request,
 *       no approval and no form field that can produce paid quota.</li>
 *   <li><b>Let one mailbox collect free trials.</b> An address that already has an
 *       account, or already has a request in flight, is refused rather than queued.</li>
 *   <li><b>Hold anyone's password.</b> The plaintext is hashed on arrival and
 *       discarded; the hash moves onto the user row at approval and is cleared from
 *       the request. It is never returned by an endpoint, never emailed, never
 *       logged, and no admin can read it.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopLeadService {

    /** How long a verified request waits for an admin before provisioning itself. */
    private static final Duration AUTO_APPROVE_AFTER = Duration.ofHours(24);

    /** How long an emailed code stays good for. */
    private static final Duration CODE_TTL = Duration.ofMinutes(15);

    /** Minimum gap between codes to the same request — throttles resends. */
    private static final Duration CODE_COOLDOWN = Duration.ofSeconds(60);

    /** Wrong tries allowed against one code before it is burned. */
    private static final int MAX_CODE_ATTEMPTS = 5;

    /** States that mean "this address already got its shop". */
    private static final List<ShopLead.Status> ALREADY_PROVISIONED =
            List.of(ShopLead.Status.APPROVED, ShopLead.Status.CONVERTED);

    private final ShopLeadRepository leadRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final HierarchyService hierarchyService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    /**
     * The inbox that reads shop-account requests.
     *
     * Separate from {@code app.admin.email}, which is the platform admin's LOGIN
     * identity. The two shared one value, so the credentials used to sign into the admin
     * console had to be a mailbox the whole sales side could read. Falls back to the
     * admin address when unset, so a deployment that has not set LEADS_EMAIL yet still
     * gets its requests.
     */
    @Value("${app.leads.email:}")
    private String leadsEmail;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    private String leadInbox() {
        return (leadsEmail != null && !leadsEmail.isBlank()) ? leadsEmail : adminEmail;
    }

    // ── Public funnel ─────────────────────────────────────────────────────

    /**
     * Take a shop-account request and email a verification code. No account exists
     * yet and none will until the code is confirmed — an unverified request is
     * invisible to the admin queue and never provisions itself.
     */
    @Transactional
    public ShopRequestStatusResponse submit(ShopLeadRequest request) {
        String email = com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail());

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("The two passwords don't match. Type the same one twice.");
        }
        requireNoExistingShop(email);

        // Coming back to an unverified request of their own re-uses the row rather than
        // piling up duplicates — the details are refreshed and a new code goes out.
        ShopLead lead = leadRepository
                .findFirstByEmailAndStatusInOrderByCreatedAtDesc(email, List.of(ShopLead.Status.PENDING_EMAIL))
                .orElseGet(ShopLead::new);

        lead.setName(request.getName().trim());
        lead.setEmail(email);
        lead.setPhone(blankToNull(request.getPhone()));
        lead.setShopName(request.getShopName().trim());
        lead.setCity(blankToNull(request.getCity()));
        lead.setState(blankToNull(request.getState()));
        lead.setNotes(blankToNull(request.getNotes()));
        lead.setStatus(ShopLead.Status.PENDING_EMAIL);
        // Hashed here and nowhere else. request.getPassword() is not referenced again,
        // and ShopLeadRequest excludes it from toString() so no logger can reach it.
        lead.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        leadRepository.save(lead);

        issueCode(lead);
        log.info("Shop account requested: id={} shop={} city={}", lead.getId(), lead.getShopName(), lead.getCity());
        return statusOf(lead);
    }

    /** Send a fresh code for a request still waiting on its mailbox. */
    @Transactional
    public ShopRequestStatusResponse resendCode(String requestId) {
        ShopLead lead = load(requestId);
        if (lead.getStatus() != ShopLead.Status.PENDING_EMAIL) {
            throw new IllegalStateException("This request's email is already verified.");
        }
        issueCode(lead);
        return statusOf(lead);
    }

    /**
     * Confirm the emailed code. This is the moment the request becomes real: it
     * enters the admin queue and starts its 24-hour clock.
     */
    @Transactional
    public ShopRequestStatusResponse verifyEmail(String requestId, String codeInput) {
        ShopLead lead = load(requestId);
        if (lead.isEmailVerified()) {
            throw new IllegalStateException("This request's email is already verified.");
        }
        if (lead.getVerificationCodeHash() == null) {
            throw new IllegalArgumentException("Request a code first.");
        }
        if (lead.getVerificationExpiresAt() == null
                || lead.getVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            lead.setVerificationCodeHash(null);
            leadRepository.save(lead);
            throw new IllegalArgumentException("That code has expired. Ask for a new one.");
        }
        if (lead.getVerificationAttempts() >= MAX_CODE_ATTEMPTS) {
            lead.setVerificationCodeHash(null);
            leadRepository.save(lead);
            throw new IllegalArgumentException("Too many incorrect attempts. Ask for a new code.");
        }
        String code = codeInput == null ? "" : codeInput.trim();
        if (!passwordEncoder.matches(code, lead.getVerificationCodeHash())) {
            lead.setVerificationAttempts(lead.getVerificationAttempts() + 1);
            leadRepository.save(lead);
            int left = Math.max(0, MAX_CODE_ATTEMPTS - lead.getVerificationAttempts());
            throw new IllegalArgumentException(
                    "Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left.");
        }

        // Someone could have registered this address in the minutes between submitting
        // and verifying, so the duplicate check runs again here rather than only on the
        // way in — this is the last gate before the request can become an account.
        requireNoExistingShop(lead.getEmail());

        LocalDateTime now = LocalDateTime.now();
        lead.setVerificationCodeHash(null);
        lead.setVerificationAttempts(0);
        lead.setEmailVerifiedAt(now);
        lead.setStatus(ShopLead.Status.AWAITING_APPROVAL);
        lead.setAutoApproveAt(now.plus(AUTO_APPROVE_AFTER));
        leadRepository.save(lead);

        sendVerifiedAcknowledgement(lead);
        notifyLeadsInbox(lead);
        log.info("Shop request {} verified; queued for approval (auto-creates at {})",
                lead.getId(), lead.getAutoApproveAt());
        return statusOf(lead);
    }

    /**
     * Refuse an address that already has a shop, or already has one coming.
     *
     * <p>This is what stops a free tier being collected repeatedly: a mailbox gets one
     * shop account, and a second request from it is turned away with an explanation
     * rather than quietly queued behind the first.
     */
    private void requireNoExistingShop(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There's already a HueVista account for this email. Sign in instead — "
                            + "or use \"Forgot password\" if you can't get in.");
        }
        List<ShopLead> history = leadRepository.findByEmailOrderByCreatedAtDesc(email);
        for (ShopLead prior : history) {
            if (ALREADY_PROVISIONED.contains(prior.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A shop account has already been created for this email. "
                                + "Sign in with it, or write to us if you've lost access.");
            }
            if (prior.getStatus() == ShopLead.Status.AWAITING_APPROVAL) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "We already have a request for this email — your account is being set up "
                                + "and you'll have it within a day.");
            }
            if (prior.getStatus() == ShopLead.Status.NEW || prior.getStatus() == ShopLead.Status.CONTACTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "We already have a request for this email and someone is on it.");
            }
        }
    }

    /** Mint, hash and email a 6-digit code, subject to the resend cooldown. */
    private void issueCode(ShopLead lead) {
        LocalDateTime now = LocalDateTime.now();
        if (lead.getVerificationSentAt() != null) {
            long since = Duration.between(lead.getVerificationSentAt(), now).getSeconds();
            if (since < CODE_COOLDOWN.getSeconds()) {
                throw new IllegalStateException("Please wait "
                        + (CODE_COOLDOWN.getSeconds() - since) + "s before asking for another code.");
            }
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        lead.setVerificationCodeHash(passwordEncoder.encode(code));
        lead.setVerificationExpiresAt(now.plus(CODE_TTL));
        lead.setVerificationSentAt(now);
        lead.setVerificationAttempts(0);
        leadRepository.save(lead);

        emailSender.send(lead.getEmail(),
                "Your HueVista verification code",
                "Hi " + lead.getName() + ",\n\n"
                        + "Your code to confirm this email for \"" + lead.getShopName() + "\" is "
                        + code + ".\n\n"
                        + "It expires in " + CODE_TTL.toMinutes() + " minutes. If you didn't ask for a "
                        + "HueVista shop account, you can ignore this email — nothing has been created.\n\n"
                        + "— HueVista");
    }

    // ── Admin queue ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ShopLeadResponse> list(int page, int size) {
        List<ShopLead> leads = leadRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
        Map<String, String> distributorNames = distributorNamesFor(leads);
        return leads.stream()
                // Most rows have no distributor yet (nothing has been approved), so the
                // lookup has to tolerate a null key rather than assume one.
                .map(l -> ShopLeadResponse.from(l, l.getDistributorOrgId() == null ? null
                        : distributorNames.get(l.getDistributorOrgId())))
                .toList();
    }

    /** One batched lookup so the queue doesn't fetch an org per row. */
    private Map<String, String> distributorNamesFor(List<ShopLead> leads) {
        List<String> ids = leads.stream()
                .map(ShopLead::getDistributorOrgId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            orgRepository.findAllById(ids).forEach(o -> names.put(o.getId(), o.getName()));
        }
        return names;
    }

    /**
     * Provision the account a verified request asked for — the admin's one click.
     *
     * @param distributorOrgId the distributor to file the shop under; blank means the house one
     */
    @Transactional
    public ShopLeadResponse approve(String adminUserId, String leadId, String distributorOrgId) {
        ShopLead lead = load(leadId);
        if (lead.getStatus() == ShopLead.Status.APPROVED) {
            throw new IllegalStateException("This request already has an account.");
        }
        if (!lead.isProvisionable()) {
            throw new IllegalStateException(lead.isEmailVerified()
                    ? "This request predates the current form and has no password on it — "
                      + "create the shop with the form above instead."
                    : "This request's email hasn't been verified yet.");
        }
        return provision(lead, adminUserId, distributorOrgId);
    }

    /** Turn a request down. Nothing is created and the address is free to try again. */
    @Transactional
    public ShopLeadResponse dismiss(String adminUserId, String leadId) {
        ShopLead lead = load(leadId);
        if (lead.getStatus() == ShopLead.Status.APPROVED) {
            throw new IllegalStateException("This request already has an account — dismissing it would change nothing.");
        }
        lead.setStatus(ShopLead.Status.DISMISSED);
        lead.setAutoApproveAt(null);
        // The password was only ever held to create the account this request will now
        // never become, so there is no reason to keep the hash a moment longer.
        lead.setPasswordHash(null);
        lead.setVerificationCodeHash(null);
        leadRepository.save(lead);
        log.info("Shop request {} dismissed by {}", leadId, adminUserId);
        return ShopLeadResponse.from(lead);
    }

    // ── The 24-hour deadline ──────────────────────────────────────────────

    /**
     * The verified requests whose 24-hour deadline has passed.
     *
     * <p>Ids rather than entities, because each one is provisioned in a transaction of
     * its own — see {@link #provisionOverdue}.
     */
    @Transactional(readOnly = true)
    public List<String> overdueRequestIds() {
        return leadRepository.findByStatusAndAutoApproveAtBefore(
                        ShopLead.Status.AWAITING_APPROVAL, LocalDateTime.now())
                .stream()
                // Requests carried over from the old funnel have no password to create an
                // account with, so the deadline leaves them for an admin.
                .filter(ShopLead::isProvisionable)
                .map(ShopLead::getId)
                .toList();
    }

    /**
     * Provision one overdue request — free tier, and the distributor nobody chose, which
     * is the house one.
     *
     * <p>Deliberately one request per transaction. Sweeping the whole batch in a single
     * transaction and catching failures inside it does not work: a failed JPA operation
     * marks the transaction rollback-only, so one shop whose address was taken in the
     * meantime would silently take every later shop in the batch down with it. Callers
     * loop and catch instead, and the caller must be a different bean or the proxy is
     * bypassed and the per-request boundary is lost.
     *
     * @see ShopRequestAutoApprovalJob
     */
    @Transactional
    public ShopLeadResponse provisionOverdue(String leadId) {
        ShopLead lead = load(leadId);
        // Re-checked under this transaction: an admin may have approved or dismissed it
        // between the sweep's query and now.
        if (lead.getStatus() != ShopLead.Status.AWAITING_APPROVAL || !lead.isProvisionable()) {
            throw new IllegalStateException("Request " + leadId + " is no longer waiting.");
        }
        return provision(lead, null, lead.getDistributorOrgId());
    }

    /** The shared body of both approval paths. */
    private ShopLead provisionOnce(ShopLead lead, String approverUserId, String distributorOrgId) {
        AdminUserResponse created = hierarchyService.createRetailerFromRequest(
                approverUserId, lead.getName(), lead.getEmail(), lead.getPhone(),
                lead.getShopName(), lead.getCity(), lead.getState(),
                lead.getPasswordHash(), distributorOrgId);

        Organization distributor = resolveDistributorOf(created.getId());
        lead.setStatus(ShopLead.Status.APPROVED);
        lead.setCreatedUserId(created.getId());
        lead.setDistributorOrgId(distributor != null ? distributor.getId() : null);
        lead.setApprovedAt(LocalDateTime.now());
        lead.setApprovedByUserId(approverUserId);
        lead.setAutoApproveAt(null);
        // The hash now lives on the user row, which is the only place it is needed.
        // Keeping a second copy on a row an admin can list would be a credential
        // sitting in a queue forever, for nothing.
        lead.setPasswordHash(null);
        lead.setVerificationCodeHash(null);
        return leadRepository.save(lead);
    }

    private ShopLeadResponse provision(ShopLead lead, String approverUserId, String distributorOrgId) {
        ShopLead saved = provisionOnce(lead, approverUserId, distributorOrgId);
        String distributorName = saved.getDistributorOrgId() == null ? null
                : orgRepository.findById(saved.getDistributorOrgId()).map(Organization::getName).orElse(null);
        sendAccountReadyEmail(saved, distributorName);
        log.info("Shop request {} provisioned as user {} ({})", saved.getId(), saved.getCreatedUserId(),
                approverUserId != null ? "approved by " + approverUserId : "24-hour deadline");
        return ShopLeadResponse.from(saved, distributorName);
    }

    /** The distributor the new shop actually ended up under, for the record and the email. */
    private Organization resolveDistributorOf(String retailerUserId) {
        return orgRepository.findByOwnerIdAndType(retailerUserId,
                        com.gridstore.huevista.account.model.OrgType.RETAILER).stream()
                .findFirst()
                .flatMap(retailerOrg -> hierarchyService.distributorOf(retailerOrg.getId()))
                .orElse(null);
    }

    // ── Emails ────────────────────────────────────────────────────────────

    /** "We have it, and you'll have an account within a day." */
    private void sendVerifiedAcknowledgement(ShopLead lead) {
        try {
            emailSender.send(lead.getEmail(),
                    "We've got your HueVista shop request",
                    "Hi " + lead.getName() + ",\n\n"
                            + "Thanks — your email is confirmed and your request for \""
                            + lead.getShopName() + "\" is with us.\n\n"
                            + "We'll set the account up shortly. If we haven't got to it within 24 hours "
                            + "it opens automatically, so either way you'll have it by this time tomorrow. "
                            + "You'll get one more email the moment it's ready.\n\n"
                            + "You'll sign in with this address and the password you just chose — "
                            + "we never store it in a readable form, so keep it somewhere safe.\n\n"
                            + "— HueVista");
        } catch (Exception e) {
            log.warn("Acknowledgement email for request {} failed: {}", lead.getId(), e.getMessage());
        }
    }

    /**
     * "Your account is ready." Names the password only to say it is the one they
     * already chose — the value itself is never in an email, because a mailbox is a
     * plaintext store that outlives the credential's usefulness.
     */
    private void sendAccountReadyEmail(ShopLead lead, String distributorName) {
        try {
            String url = firstFrontendOrigin();
            emailSender.send(lead.getEmail(),
                    "Your HueVista shop account is ready",
                    "Hi " + lead.getName() + ",\n\n"
                            + "Your HueVista shop account for \"" + lead.getShopName() + "\" is open.\n\n"
                            + "Sign in:  " + url + "/sign-in\n"
                            + "Email:    " + lead.getEmail() + "\n"
                            + "Password: the one you chose when you requested the account.\n\n"
                            + (distributorName != null ? "Your distributor: " + distributorName + "\n\n" : "")
                            + "You're on the free plan — enough to photograph a room, mask it and show a "
                            + "customer, at no cost and with no card. When you want more, you can buy a "
                            + "plan from inside the app; nothing is charged until you do.\n\n"
                            + "Forgotten the password already? Set a new one here:\n"
                            + url + "/sign-in/forgot\n\n"
                            + "— HueVista");
        } catch (Exception e) {
            log.warn("Account-ready email for request {} failed: {}", lead.getId(), e.getMessage());
        }
    }

    /** Best-effort heads-up to the leads inbox — a failure never loses the request. */
    private void notifyLeadsInbox(ShopLead lead) {
        String inbox = leadInbox();
        if (inbox == null || inbox.isBlank()) return;
        try {
            emailSender.send(inbox,
                    "New shop account request: " + lead.getShopName(),
                    "A shop asked for a HueVista account and confirmed their email.\n\n"
                            + "Shop:   " + lead.getShopName() + "\n"
                            + "Owner:  " + lead.getName() + "\n"
                            + "Email:  " + lead.getEmail() + "\n"
                            + "Phone:  " + (lead.getPhone() != null ? lead.getPhone() : "—") + "\n"
                            + "Place:  " + (lead.getCity() != null ? lead.getCity() : "—")
                            + (lead.getState() != null ? ", " + lead.getState() : "") + "\n"
                            + (lead.getNotes() != null ? "\nNotes:\n" + lead.getNotes() + "\n" : "")
                            + "\nOpen the admin page, choose their distributor and press Create account. "
                            + "If nobody does, it opens by itself at " + lead.getAutoApproveAt() + ".");
        } catch (Exception e) {
            log.warn("Leads-inbox notification for request {} failed: {}", lead.getId(), e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ShopLead load(String leadId) {
        return leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found: " + leadId));
    }

    private ShopRequestStatusResponse statusOf(ShopLead lead) {
        return ShopRequestStatusResponse.builder()
                .requestId(lead.getId())
                .email(maskEmail(lead.getEmail()))
                .expiresInSeconds((int) CODE_TTL.getSeconds())
                .cooldownSeconds((int) CODE_COOLDOWN.getSeconds())
                .status(lead.getStatus().name())
                .build();
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** The first configured CORS origin is the frontend base URL; fall back to local dev. */
    private String firstFrontendOrigin() {
        if (allowedOrigins != null) {
            for (String o : allowedOrigins.split(",")) {
                String t = o.trim();
                if (!t.isEmpty() && !"*".equals(t)) {
                    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
                }
            }
        }
        return "http://localhost:3000";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
