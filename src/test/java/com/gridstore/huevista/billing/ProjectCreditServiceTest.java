package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
import com.gridstore.huevista.billing.service.BillingEmailService;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.ProjectCreditLedger;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectAccessService;
import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The project money path: a payment is credited at most once (replay protection), only
 * with a valid signature, and only when the paid order is one WE created, for THIS user,
 * at a price we would actually have quoted.
 *
 * The prices themselves matter here as much as the plumbing: a project costs Rs. 50 with
 * a live plan and Rs. 99 without, and a reopen costs Rs. 9 — three different amounts on
 * one endpoint, which is exactly the shape that lets a client pay the cheapest and claim
 * the dearest if the amount is not checked.
 */
class ProjectCreditServiceTest {

    private static final int SUBSCRIBED_PAISE = 5000;    // Rs. 50 with a plan
    private static final int UNSUBSCRIBED_PAISE = 9900;  // Rs. 99 without
    private static final int REOPEN_PAISE = 900;         // Rs. 9 to reopen
    private static final int VALID_DAYS = 30;

    private RazorpayClient razorpay;
    private ProjectCreditPaymentRepository payments;
    private ProjectCreditLedger ledger;
    private ProjectRepository projects;
    private BillingService billing;
    private PricingService pricing;
    private ProjectAccessService access;
    private ProjectCreditService svc;

    @BeforeEach
    void setUp() {
        razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        payments = mock(ProjectCreditPaymentRepository.class);
        ledger = mock(ProjectCreditLedger.class);
        projects = mock(ProjectRepository.class);
        billing = mock(BillingService.class);

        pricing = new PricingService(billing, mock(OrgMembershipRepository.class));
        ReflectionTestUtils.setField(pricing, "projectSubscribedPaise", SUBSCRIBED_PAISE);
        ReflectionTestUtils.setField(pricing, "projectUnsubscribedPaise", UNSUBSCRIBED_PAISE);
        ReflectionTestUtils.setField(pricing, "projectReopenPaise", REOPEN_PAISE);
        ReflectionTestUtils.setField(pricing, "projectValidDays", VALID_DAYS);
        ReflectionTestUtils.setField(pricing, "currency", "INR");

        access = new ProjectAccessService(projects, billing, pricing);
        svc = new ProjectCreditService(razorpay, payments, ledger, projects, access, pricing,
                mock(BillingEmailService.class));
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
        ReflectionTestUtils.setField(svc, "keyId", "key");

        // Default: nobody is subscribed. Individual tests opt in.
        when(billing.findEntitlingSubscription(any())).thenReturn(Optional.empty());
    }

    private VerifyProjectCreditRequest req(String order, String payment, String sig) {
        VerifyProjectCreditRequest r = new VerifyProjectCreditRequest();
        r.setOrderId(order);
        r.setPaymentId(payment);
        r.setSignature(sig);
        return r;
    }

    private static Order order(int amount, String purpose, String userId) {
        return order(amount, purpose, userId, null);
    }

    private static Order order(int amount, String purpose, String userId, String projectId) {
        JSONObject json = new JSONObject();
        json.put("amount", amount);
        JSONObject notes = new JSONObject();
        notes.put("purpose", purpose);
        notes.put("userId", userId);
        if (projectId != null) {
            notes.put("projectId", projectId);
        }
        json.put("notes", notes);
        return new Order(json);
    }

    @Test
    void verifiedPaymentCreditsOnce_thenReplayIsRejected() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(UNSUBSCRIBED_PAISE, "project_credit", "user-1"));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);

            // First redemption: payment not seen before -> issues exactly one credit.
            when(payments.existsByPaymentId("pay_1")).thenReturn(false);
            svc.verifyAndCredit("user-1", req("order_1", "pay_1", "sig"));
            verify(ledger, times(1)).issue(eq("user-1"), eq(UNSUBSCRIBED_PAISE), eq(VALID_DAYS), any());
            verify(payments, times(1)).saveAndFlush(any());

            // Replay of the same (still-valid) payment: rejected, no extra credit.
            when(payments.existsByPaymentId("pay_1")).thenReturn(true);
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_1", "pay_1", "sig")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been redeemed");
            verify(ledger, times(1)).issue(any(), anyInt(), anyInt(), any());
        }
    }

    @Test
    void invalidSignatureIsRejectedAndNothingCredited() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(false);
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_1", "pay_x", "bad")))
                    .isInstanceOf(SecurityException.class);
            verifyNoInteractions(ledger);
            verify(payments, never()).saveAndFlush(any());
        }
    }

    @Test
    void paymentForDifferentOrderIsRejected() throws Exception {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);

            // An amount that is neither of our project prices — e.g. a cheaper order paid
            // on the same merchant account and replayed here.
            when(razorpay.orders.fetch("order_cheap")).thenReturn(order(100, "project_credit", "user-1"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_cheap", "pay_a", "sig")))
                    .isInstanceOf(SecurityException.class);

            // The Rs. 9 reopen price must not buy a whole project.
            when(razorpay.orders.fetch("order_reopen_price"))
                    .thenReturn(order(REOPEN_PAISE, "project_credit", "user-1"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_reopen_price", "pay_r", "sig")))
                    .isInstanceOf(SecurityException.class);

            // Wrong purpose (an order created for something else entirely).
            when(razorpay.orders.fetch("order_other"))
                    .thenReturn(order(UNSUBSCRIBED_PAISE, "subscription", "user-1"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_other", "pay_b", "sig")))
                    .isInstanceOf(SecurityException.class);

            // Order created for a different user.
            when(razorpay.orders.fetch("order_theirs"))
                    .thenReturn(order(UNSUBSCRIBED_PAISE, "project_credit", "user-2"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_theirs", "pay_c", "sig")))
                    .isInstanceOf(SecurityException.class);

            verifyNoInteractions(ledger);
            verify(payments, never()).saveAndFlush(any());
        }
    }

    /**
     * The subscribed price is still honoured on verify even for an account that reads as
     * unsubscribed right now — a plan can lapse between order and payment, and eating a
     * customer's money over that timing accident would be ours to answer for.
     */
    @Test
    void bothProjectPricesAreAccepted() throws Exception {
        when(razorpay.orders.fetch("order_sub"))
                .thenReturn(order(SUBSCRIBED_PAISE, "project_credit", "user-1"));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);
            svc.verifyAndCredit("user-1", req("order_sub", "pay_s", "sig"));
            verify(ledger, times(1)).issue(eq("user-1"), eq(SUBSCRIBED_PAISE), eq(VALID_DAYS), any());
        }
    }

    @Test
    void reopenExtendsTheProjectsWindowByThirtyDays() throws Exception {
        Project lapsed = Project.builder()
                .id("proj-1")
                .image(UploadedImage.builder().id("img-1").build())
                .accessExpiresAt(LocalDateTime.now().minusDays(3))
                .build();
        when(projects.findByIdAndUserId("proj-1", "user-1")).thenReturn(Optional.of(lapsed));
        when(razorpay.orders.fetch("order_reopen"))
                .thenReturn(order(REOPEN_PAISE, "project_reopen", "user-1", "proj-1"));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);
            svc.verifyAndReopen("user-1", req("order_reopen", "pay_ro", "sig"));
        }

        // Extended from NOW, not from the expiry three days ago — a lapsed window that
        // resumed from its old end would hand back 27 days for a full price.
        assertThat(lapsed.getAccessExpiresAt())
                .isAfter(LocalDateTime.now().plusDays(VALID_DAYS - 1))
                .isBefore(LocalDateTime.now().plusDays(VALID_DAYS + 1));
        verify(projects).save(lapsed);
    }

    /**
     * The project a reopen touches comes from the ORDER, never from the request body:
     * the signature proves a payment is genuine but says nothing about what it bought, so
     * a client-supplied id would let one Rs. 9 payment reopen anything they could name.
     */
    @Test
    void reopenWithoutAProjectOnTheOrderIsRejected() throws Exception {
        when(razorpay.orders.fetch("order_bare"))
                .thenReturn(order(REOPEN_PAISE, "project_reopen", "user-1", null));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);
            assertThatThrownBy(() -> svc.verifyAndReopen("user-1", req("order_bare", "pay_x", "sig")))
                    .isInstanceOf(SecurityException.class);
        }
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void reopeningAProjectThatIsStillOpenIsRefused() {
        Project open = Project.builder()
                .id("proj-2")
                .accessExpiresAt(LocalDateTime.now().plusDays(5))
                .build();
        when(projects.findByIdAndUserId("proj-2", "user-1")).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> svc.createReopenOrder("user-1", "proj-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still open");
    }

    @Test
    void optionsQuoteBothPricesSoALapseIsNeverASurprise() {
        var options = svc.getOptions("user-1");
        assertThat(options.isSubscribed()).isFalse();
        assertThat(options.getProjectPricePaise()).isEqualTo(UNSUBSCRIBED_PAISE);
        assertThat(options.getSubscribedProjectPricePaise()).isEqualTo(SUBSCRIBED_PAISE);
        assertThat(options.getUnsubscribedProjectPricePaise()).isEqualTo(UNSUBSCRIBED_PAISE);
        assertThat(options.getReopenPricePaise()).isEqualTo(REOPEN_PAISE);
        assertThat(options.getValidDays()).isEqualTo(VALID_DAYS);
    }
}
