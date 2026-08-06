package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.repository.PaymentAttemptRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.ratelimit.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Set;

/**
 * Opens and advances {@link PaymentAttempt} rows.
 *
 * <p>Like {@link com.gridstore.huevista.common.audit.AuditService}, every write here runs
 * in its OWN transaction and swallows its own failures. A payment must never fail because
 * we could not write a note about it, and — just as important — a payment that succeeds
 * must not be rolled back by a bad note. The report being incomplete is a much smaller
 * problem than the report being able to break checkout.
 *
 * <h2>What is trusted</h2>
 * The amount, the plan, the user and the reference all come from the server side at
 * {@link #open} time. The browser can only ever supply context about an attempt that
 * already exists — the page URL it was on, and the gateway's error payload — and only for
 * a reference it can name. It can never create a row, change an amount, or move an
 * attempt to PAID. That last one matters: PAID is written exclusively by the verify paths
 * that checked a real signature.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentAttemptService {

    private final PaymentAttemptRepository repository;
    private final UserRepository userRepository;

    @Value("${app.rate-limit.trust-forwarded-headers:true}")
    private boolean trustForwardedHeaders;

    @Value("${app.rate-limit.trusted-proxy-hops:1}")
    private int trustedProxyHops;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Long strings are truncated rather than dropped — a clipped URL still identifies a page. */
    private static final int MAX_URL = 1024;
    private static final int MAX_UA = 512;
    private static final int MAX_ERR_DESC = 512;
    private static final int MAX_SHORT = 128;

    /**
     * The only transitions a BROWSER may ask for. Everything else — PAID above all — is
     * reserved for server-side code that verified something. Without this set, the event
     * endpoint would be an unauthenticated "mark my order paid" button in the report.
     */
    private static final Set<PaymentAttemptStatus> CLIENT_REPORTABLE = EnumSet.of(
            PaymentAttemptStatus.OPENED,
            PaymentAttemptStatus.ABANDONED,
            PaymentAttemptStatus.FAILED,
            PaymentAttemptStatus.VERIFY_FAILED);

    public static boolean isClientReportable(PaymentAttemptStatus status) {
        return CLIENT_REPORTABLE.contains(status);
    }

    /**
     * Record that a checkout has been handed to a buyer.
     *
     * <p>Called from the order/subscription creation paths, so it runs while the HTTP
     * request that asked for the order is still on the thread — that is where the IP and
     * user agent come from. Returns quietly on any failure; callers treat this as
     * fire-and-forget.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void open(String reference, PaymentFlow flow, String userId,
                     int amountPaise, String currency, String description, String plan) {
        try {
            if (reference == null || reference.isBlank()) return;
            // Razorpay can hand back an id we have seen before only in reuse paths (a
            // pending subscription offered again). Keep the original row and note the
            // re-offer rather than exploding on the unique index.
            var existing = repository.findByReference(reference).orElse(null);
            if (existing != null) {
                append(existing, existing.getStatus(), "checkout offered again");
                repository.save(existing);
                return;
            }

            HttpServletRequest request = currentRequest();
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .reference(reference)
                    .flow(flow)
                    .status(PaymentAttemptStatus.CREATED)
                    .userId(userId)
                    .userEmail(emailOf(userId))
                    .amountPaise(Math.max(0, amountPaise))
                    .currency(currency == null || currency.isBlank() ? "INR" : currency)
                    .description(clip(description, 200))
                    .plan(clip(plan, 32))
                    .userAgent(request == null ? null : clip(request.getHeader("User-Agent"), MAX_UA))
                    .ipAddress(request == null ? null
                            : clip(ClientIps.clientIp(request, trustForwardedHeaders, trustedProxyHops), 64))
                    // The Referer of the API call IS the page the buyer clicked Pay on, so
                    // the report is useful even for a client that never reports an event.
                    // A browser report can refine it later with the full URL.
                    .pageUrl(request == null ? null : clip(request.getHeader("Referer"), MAX_URL))
                    .build();
            append(attempt, PaymentAttemptStatus.CREATED, describeOpen(flow, amountPaise));
            repository.save(attempt);
        } catch (Exception e) {
            log.warn("Payment attempt open failed for reference={}: {}", reference, e.getMessage());
        }
    }

    /** Attach the shop a kiosk attempt belongs to; the walk-in buyer has no account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attachOrganization(String reference, String organizationId) {
        mutate(reference, a -> a.setOrganizationId(organizationId));
    }

    /**
     * Move an attempt to a new status, on our own authority.
     *
     * <p>Server-side transitions outrank anything a browser said, because they are the
     * only ones based on a checked signature. That matters in a case that is ordinary
     * rather than exotic: Razorpay's dismiss callback fires when the modal closes, which
     * in some flows includes the automatic close after a payment SUCCEEDS. The browser
     * guards against reporting that, but a slow verify or a duplicated event can still
     * land an ABANDONED just before the PAID — and an audit that files completed sales
     * under "buyer walked away" is worse than no audit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transition(String reference, PaymentAttemptStatus status, String note) {
        mutate(reference, a -> applyStatus(a, status, note, true));
    }

    /** Terminal success, written only where a signature was actually verified. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaid(String reference, String paymentId) {
        mutate(reference, a -> {
            if (paymentId != null && !paymentId.isBlank()) a.setPaymentId(paymentId);
            applyStatus(a, PaymentAttemptStatus.PAID,
                    "verified" + (paymentId == null ? "" : " · " + paymentId), true);
        });
    }

    /**
     * The buyer was charged but we could not complete the purchase. The single most
     * important row in the report: real money, nothing delivered.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markVerifyFailed(String reference, String paymentId, String reason) {
        mutate(reference, a -> {
            if (paymentId != null && !paymentId.isBlank()) a.setPaymentId(paymentId);
            a.setFailureNote(clip(reason, 2000));
            applyStatus(a, PaymentAttemptStatus.VERIFY_FAILED, clip(reason, MAX_SHORT), true);
        });
        log.error("Payment verification failed — money may have left the buyer: reference={} payment={} reason={}",
                reference, paymentId, reason);
    }

    /**
     * Context reported by the browser: the exact page, the referrer, and — when Razorpay
     * refused the payment — its error payload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordClientEvent(String reference, PaymentAttemptStatus status, String pageUrl,
                                  String referrer, String paymentId, String errorCode,
                                  String errorDescription, String errorSource, String errorStep,
                                  String errorReason) {
        mutate(reference, a -> {
            // The browser's URL is more precise than the Referer header we opened with
            // (which some browsers strip to an origin), so it wins when offered.
            if (hasText(pageUrl)) a.setPageUrl(clip(pageUrl, MAX_URL));
            if (hasText(referrer)) a.setReferrer(clip(referrer, MAX_URL));
            if (hasText(paymentId)) a.setPaymentId(clip(paymentId, 255));
            if (hasText(errorCode)) a.setErrorCode(clip(errorCode, 64));
            if (hasText(errorDescription)) a.setErrorDescription(clip(errorDescription, MAX_ERR_DESC));
            if (hasText(errorSource)) a.setErrorSource(clip(errorSource, 64));
            if (hasText(errorStep)) a.setErrorStep(clip(errorStep, 64));
            if (hasText(errorReason)) a.setErrorReason(clip(errorReason, MAX_SHORT));
            applyStatus(a, status, hasText(errorDescription) ? clip(errorDescription, MAX_SHORT) : null, false);
        });
    }

    /**
     * Run a verification and record how it ended.
     *
     * <p>Callers invoke this from a CONTROLLER, outside any transaction, and that placement
     * is the point. The verify methods are {@code @Transactional}, and marking an attempt
     * PAID from inside one would commit that claim — these writes use their own transaction
     * — even if the surrounding purchase then rolled back. Called from outside, the
     * purchase has already committed, so PAID means the buyer really did get the thing.
     *
     * <p>Failures are re-thrown untouched: the buyer sees exactly the error they always saw.
     * The only difference is that afterwards there is a row explaining it with the payment
     * id attached, which is the difference between "we think you weren't charged" and
     * knowing which of the two of us is holding the money.
     */
    public <T> T recordVerification(String reference, String paymentId, java.util.function.Supplier<T> verify) {
        T result;
        try {
            result = verify.get();
        } catch (RuntimeException e) {
            markVerifyFailed(reference, paymentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        markPaid(reference, paymentId);
        return result;
    }

    /** The attempt behind a reference, for the ownership check on the event endpoint. */
    @Transactional(readOnly = true)
    public PaymentAttempt find(String reference) {
        return repository.findByReference(reference).orElse(null);
    }

    /**
     * Close attempts nobody ever reported back on.
     *
     * <p>An attempt stuck at CREATED or OPENED is not "still in progress" an hour later —
     * Checkout sessions do not live that long. Left alone they would pile up and make the
     * report's abandonment count read low, which is the one number it exists to get right.
     * Returns how many were closed.
     */
    @Transactional
    public int closeStale(java.time.Duration olderThan, int limit) {
        var stale = repository.findStaleOpen(LocalDateTime.now().minus(olderThan), PageRequest.of(0, limit));
        for (PaymentAttempt a : stale) {
            applyStatus(a, PaymentAttemptStatus.ABANDONED,
                    "no outcome reported — closed by sweeper", true);
        }
        repository.saveAll(stale);
        if (!stale.isEmpty()) {
            log.info("Closed {} stale payment attempts older than {}", stale.size(), olderThan);
        }
        return stale.size();
    }

    // ---- internals ----------------------------------------------------------------

    private void mutate(String reference, java.util.function.Consumer<PaymentAttempt> change) {
        try {
            if (reference == null || reference.isBlank()) return;
            repository.findByReference(reference).ifPresent(a -> {
                change.accept(a);
                repository.save(a);
            });
        } catch (Exception e) {
            log.warn("Payment attempt update failed for reference={}: {}", reference, e.getMessage());
        }
    }

    /**
     * @param authoritative true for a transition WE decided (a verified signature, the
     *     stale sweeper); false for one a browser reported. Only an authoritative
     *     transition may overrule an ending that is already recorded — a client report
     *     that arrives late can still add context, but it cannot rewrite the outcome.
     */
    private void applyStatus(PaymentAttempt a, PaymentAttemptStatus next, String note,
                             boolean authoritative) {
        if (!authoritative && a.getStatus() != null && a.getStatus().isTerminal()
                && next != a.getStatus()) {
            append(a, next, "late client report ignored — already " + a.getStatus());
            return;
        }
        // Even on our own authority, PAID is final: nothing that happens after a verified
        // payment un-sells it, and a refund is a different record entirely.
        if (a.getStatus() == PaymentAttemptStatus.PAID && next != PaymentAttemptStatus.PAID) {
            append(a, next, "ignored — already PAID");
            return;
        }
        if (next == PaymentAttemptStatus.OPENED && a.getOpenedAt() == null) {
            a.setOpenedAt(LocalDateTime.now());
        }
        if (next.isTerminal() && a.getClosedAt() == null) {
            a.setClosedAt(LocalDateTime.now());
        }
        a.setStatus(next);
        append(a, next, note);
    }

    /** Append one line to the attempt's story. Oldest first, so it reads top to bottom. */
    private void append(PaymentAttempt a, PaymentAttemptStatus status, String note) {
        String line = LocalDateTime.now().format(STAMP) + "  " + status
                + (hasText(note) ? "  " + note : "");
        String existing = a.getTimeline();
        a.setTimeline(hasText(existing) ? existing + "\n" + line : line);
    }

    private static String describeOpen(PaymentFlow flow, int amountPaise) {
        return flow.getDisplayName()
                + (amountPaise > 0 ? " · " + String.format("%.2f", amountPaise / 100.0) : "");
    }

    /**
     * The buyer's e-mail, copied onto the row at open time. Looked up once and stored,
     * rather than joined at read time, because the report has to keep naming somebody
     * after the account is gone — which is exactly when a payment dispute surfaces.
     */
    private String emailOf(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return userRepository.findById(userId).map(u -> u.getEmail()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The request in flight, or null when called off a webhook/scheduler thread. */
    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
