package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.repository.PaymentAttemptRepository;
import com.gridstore.huevista.billing.service.PaymentAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The payment audit's rules about what may overwrite what.
 *
 * These matter more than they look. Some of this table is written by a BROWSER — the only
 * thing that can see a buyer close a Checkout window — and browser reports arrive late,
 * twice, and after the payment they were about has already succeeded. Getting the
 * precedence wrong files completed sales under "buyer walked away", which is worse than
 * having no report at all.
 */
class PaymentAttemptServiceTest {

    private static final String REF = "order_abc123";
    private static final String USER_ID = "user-1";

    private Map<String, PaymentAttempt> store;
    private PaymentAttemptRepository repository;
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        store = new LinkedHashMap<>();
        repository = mock(PaymentAttemptRepository.class);

        when(repository.findByReference(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repository.save(any(PaymentAttempt.class))).thenAnswer(inv -> {
            PaymentAttempt a = inv.getArgument(0);
            if (a.getId() == null) a.setId("id-" + store.size());
            // The real @CreationTimestamp is applied by Hibernate on insert.
            if (a.getCreatedAt() == null) a.setCreatedAt(LocalDateTime.now());
            store.put(a.getReference(), a);
            return a;
        });

        UserRepository users = mock(UserRepository.class);
        User buyer = new User();
        buyer.setId(USER_ID);
        buyer.setEmail("shop@example.com");
        when(users.findById(USER_ID)).thenReturn(Optional.of(buyer));

        service = new PaymentAttemptService(repository, users);
        ReflectionTestUtils.setField(service, "trustForwardedHeaders", true);
        ReflectionTestUtils.setField(service, "trustedProxyHops", 1);
    }

    /** Puts a request on the thread, the way a real order-creation call would have one. */
    private void withRequest(String referer, String userAgent, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (referer != null) request.addHeader("Referer", referer);
        if (userAgent != null) request.addHeader("User-Agent", userAgent);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private PaymentAttempt saved() {
        return store.get(REF);
    }

    @Test
    void opensAnAttemptWithTheBuyersContext() {
        withRequest("https://huevista.in/plan", "Mozilla/5.0 (Pixel 8)", "203.0.113.9");
        try {
            service.open(REF, PaymentFlow.PROJECT, USER_ID, 6500, "INR", "1 extra project", "STARTER");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        PaymentAttempt a = saved();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.CREATED);
        assertThat(a.getFlow()).isEqualTo(PaymentFlow.PROJECT);
        assertThat(a.getAmountPaise()).isEqualTo(6500);
        // Copied at open time so the report still names somebody after the account is gone.
        assertThat(a.getUserEmail()).isEqualTo("shop@example.com");
        // Even with no browser report, the API call's Referer already says which page it was.
        assertThat(a.getPageUrl()).isEqualTo("https://huevista.in/plan");
        assertThat(a.getUserAgent()).isEqualTo("Mozilla/5.0 (Pixel 8)");
        assertThat(a.getIpAddress()).isEqualTo("203.0.113.9");
        assertThat(a.getTimeline()).contains("CREATED");
    }

    @Test
    void worksWithNoRequestOnTheThread() {
        // Webhook and scheduler threads have no request; that must not lose the row.
        service.open(REF, PaymentFlow.SUBSCRIPTION, USER_ID, 99900, "INR", "Business plan", "BUSINESS");

        assertThat(saved()).isNotNull();
        assertThat(saved().getIpAddress()).isNull();
    }

    @Test
    void aRepeatedReferenceNotesTheReOfferInsteadOfFailing() {
        service.open(REF, PaymentFlow.SUBSCRIPTION, USER_ID, 99900, "INR", "Business plan", "BUSINESS");
        service.open(REF, PaymentFlow.SUBSCRIPTION, USER_ID, 99900, "INR", "Business plan", "BUSINESS");

        assertThat(store).hasSize(1);
        assertThat(saved().getTimeline()).contains("checkout offered again");
    }

    @Test
    void recordsTheBrowsersAbandonmentWithThePageAndTime() {
        service.open(REF, PaymentFlow.POINTS, USER_ID, 50000, "INR", "500 points", null);

        service.recordClientEvent(REF, PaymentAttemptStatus.OPENED,
                "https://huevista.in/plan?upgrade=1", "https://huevista.in/pricing",
                null, null, null, null, null, null);
        service.recordClientEvent(REF, PaymentAttemptStatus.ABANDONED,
                "https://huevista.in/plan?upgrade=1", null, null, null, null, null, null, null);

        PaymentAttempt a = saved();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.ABANDONED);
        // The browser's URL is more precise than a Referer, which some browsers trim.
        assertThat(a.getPageUrl()).isEqualTo("https://huevista.in/plan?upgrade=1");
        assertThat(a.getReferrer()).isEqualTo("https://huevista.in/pricing");
        assertThat(a.getOpenedAt()).isNotNull();
        assertThat(a.getClosedAt()).isNotNull();
        assertThat(a.durationSeconds()).isNotNull();
    }

    @Test
    void keepsTheGatewaysOwnReasonForARefusal() {
        service.open(REF, PaymentFlow.PROJECT, USER_ID, 6500, "INR", "1 extra project", "FREE");

        service.recordClientEvent(REF, PaymentAttemptStatus.FAILED, "https://huevista.in/studio", null,
                "pay_failed_1", "BAD_REQUEST_ERROR", "Insufficient funds", "bank",
                "payment_authorization", "payment_failed");

        PaymentAttempt a = saved();
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(a.getPaymentId()).isEqualTo("pay_failed_1");
        assertThat(a.getErrorCode()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(a.getErrorSource()).isEqualTo("bank");
        assertThat(a.getErrorStep()).isEqualTo("payment_authorization");
    }

    @Test
    void aVerifiedPaymentOverrulesAnAbandonmentTheBrowserAlreadyReported() {
        service.open(REF, PaymentFlow.SUBSCRIPTION, USER_ID, 99900, "INR", "Business plan", "BUSINESS");
        // Razorpay's dismiss callback can fire on the way out of a SUCCESSFUL checkout, so
        // this ordering is ordinary rather than exotic.
        service.recordClientEvent(REF, PaymentAttemptStatus.ABANDONED, null, null, null,
                null, null, null, null, null);

        service.markPaid(REF, "pay_ok_1");

        assertThat(saved().getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(saved().getPaymentId()).isEqualTo("pay_ok_1");
    }

    @Test
    void aLateBrowserReportCannotUndoAVerifiedPayment() {
        service.open(REF, PaymentFlow.SUBSCRIPTION, USER_ID, 99900, "INR", "Business plan", "BUSINESS");
        service.markPaid(REF, "pay_ok_1");

        service.recordClientEvent(REF, PaymentAttemptStatus.ABANDONED, null, null, null,
                null, null, null, null, null);

        assertThat(saved().getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(saved().getTimeline()).contains("already PAID");
    }

    @Test
    void aBrowserMayNotDeclareAPaymentGood() {
        // The event endpoint is public for the kiosk, so this set is the thing standing
        // between the report and an "mark my order paid" button.
        assertThat(PaymentAttemptService.isClientReportable(PaymentAttemptStatus.PAID)).isFalse();
        assertThat(PaymentAttemptService.isClientReportable(PaymentAttemptStatus.CREATED)).isFalse();
        assertThat(PaymentAttemptService.isClientReportable(PaymentAttemptStatus.ABANDONED)).isTrue();
        assertThat(PaymentAttemptService.isClientReportable(PaymentAttemptStatus.FAILED)).isTrue();
    }

    @Test
    void recordVerificationMarksPaidOnSuccess() {
        service.open(REF, PaymentFlow.POINTS, USER_ID, 50000, "INR", "500 points", null);

        String out = service.recordVerification(REF, "pay_ok_2", () -> "credited");

        assertThat(out).isEqualTo("credited");
        assertThat(saved().getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
    }

    @Test
    void recordVerificationRecordsMoneyAtRiskAndStillThrows() {
        service.open(REF, PaymentFlow.POINTS, USER_ID, 50000, "INR", "500 points", null);

        assertThatThrownBy(() -> service.recordVerification(REF, "pay_taken_1", () -> {
            throw new SecurityException("Payment verification failed.");
        })).isInstanceOf(SecurityException.class);

        PaymentAttempt a = saved();
        // The buyer has paid and has nothing. This row is the only thing that says so.
        assertThat(a.getStatus()).isEqualTo(PaymentAttemptStatus.VERIFY_FAILED);
        assertThat(a.getStatus().isMoneyAtRisk()).isTrue();
        assertThat(a.getPaymentId()).isEqualTo("pay_taken_1");
        assertThat(a.getFailureNote()).contains("Payment verification failed.");
    }

    @Test
    void closesAttemptsNobodyEverReportedBackOn() {
        PaymentAttempt stale = PaymentAttempt.builder()
                .id("stale-1").reference("order_stale").flow(PaymentFlow.PROJECT)
                .status(PaymentAttemptStatus.OPENED).amountPaise(6500)
                .createdAt(LocalDateTime.now().minusHours(3))
                .build();
        List<PaymentAttempt> found = new ArrayList<>(List.of(stale));
        when(repository.findStaleOpen(any(), any())).thenReturn(found);
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.closeStale(Duration.ofHours(1), 500);

        assertThat(closed).isEqualTo(1);
        // Counted as abandonment, because that is what it is — but labelled so nobody
        // mistakes it for a buyer who was actually seen clicking away.
        assertThat(stale.getStatus()).isEqualTo(PaymentAttemptStatus.ABANDONED);
        assertThat(stale.getClosedAt()).isNotNull();
        assertThat(stale.getTimeline()).contains("sweeper");
    }

    @Test
    void anUnknownReferenceIsIgnoredRatherThanCreatingARow() {
        // The event endpoint is public; a prober naming a reference that does not exist
        // must not be able to conjure audit rows.
        service.recordClientEvent("order_never_seen", PaymentAttemptStatus.ABANDONED,
                "https://evil.example/x", null, null, null, null, null, null, null);

        assertThat(store).isEmpty();
    }

    @Test
    void anAuditFailureNeverEscapesIntoThePaymentFlow() {
        when(repository.save(any(PaymentAttempt.class)))
                .thenThrow(new RuntimeException("audit table is on fire"));

        // A payment must not fail because we could not write a note about it.
        service.open(REF, PaymentFlow.PROJECT, USER_ID, 6500, "INR", "1 extra project", "FREE");
    }
}
