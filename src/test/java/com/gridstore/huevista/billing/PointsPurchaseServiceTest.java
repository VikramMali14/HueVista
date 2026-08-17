package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.dto.VerifyPointsPurchaseRequest;
import com.gridstore.huevista.billing.repository.PointsPurchaseRepository;
import com.gridstore.huevista.billing.service.BillingEmailService;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PointsPurchaseService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.RewardPointsService;
import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one cash path left besides a subscription, so the guards matter more than usual:
 * the client asks for a COUNT and never an amount, a valid signature is not on its own
 * proof the payment was for this order or this user, and one payment credits once.
 */
class PointsPurchaseServiceTest {

    private static final String USER = "user-1";
    private static final int MIN = 100;
    private static final int MAX = 100_000;

    private RazorpayClient razorpay;
    private PointsPurchaseRepository purchases;
    private RewardPointsService points;
    private PricingService pricing;
    private com.gridstore.huevista.auth.repository.UserRepository users;
    private PointsPurchaseService svc;

    @BeforeEach
    void setUp() {
        razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        purchases = mock(PointsPurchaseRepository.class);
        points = mock(RewardPointsService.class);
        users = mock(com.gridstore.huevista.auth.repository.UserRepository.class);
        // The buyer is a shop unless a test says otherwise — points are retailer-only,
        // and order creation refuses anyone who could never be credited.
        when(users.findById(USER)).thenReturn(java.util.Optional.of(retailer()));

        pricing = new PricingService(mock(BillingService.class), mock(com.gridstore.huevista.billing.service.UnbilledAccounts.class),
                mock(OrgMembershipRepository.class), users);
        ReflectionTestUtils.setField(pricing, "rupeesPerPoint", 1);
        ReflectionTestUtils.setField(pricing, "pointsMinPurchase", MIN);
        ReflectionTestUtils.setField(pricing, "pointsMaxPurchase", MAX);
        ReflectionTestUtils.setField(pricing, "pointsValidityDays", 365);
        ReflectionTestUtils.setField(pricing, "currency", "INR");

        svc = new PointsPurchaseService(razorpay, purchases, points, pricing,
                mock(BillingEmailService.class), users,
                mock(com.gridstore.huevista.billing.service.PaymentAttemptService.class));
        ReflectionTestUtils.setField(svc, "keyId", "rzp_key");
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
    }

    private static com.gridstore.huevista.auth.model.User userWithRole(
            com.gridstore.huevista.auth.model.UserRole role) {
        com.gridstore.huevista.auth.model.User u = new com.gridstore.huevista.auth.model.User();
        u.setId(USER);
        u.setRole(role);
        return u;
    }

    private static com.gridstore.huevista.auth.model.User retailer() {
        return userWithRole(com.gridstore.huevista.auth.model.UserRole.RETAILER);
    }

    private VerifyPointsPurchaseRequest req() {
        VerifyPointsPurchaseRequest r = new VerifyPointsPurchaseRequest();
        r.setOrderId("order_1");
        r.setPaymentId("pay_1");
        r.setSignature("sig");
        return r;
    }

    private static Order order(int amountPaise, String purpose, String userId, Integer points) {
        JSONObject json = new JSONObject();
        json.put("amount", amountPaise);
        JSONObject notes = new JSONObject();
        notes.put("purpose", purpose);
        notes.put("userId", userId);
        if (points != null) {
            notes.put("points", String.valueOf(points));
        }
        json.put("notes", notes);
        return new Order(json);
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    /** One rupee, one point — and the amount is ours to compute, never the client's. */
    @Test
    void theAmountIsDerivedFromTheCountAtTheConfiguredRate() throws Exception {
        when(razorpay.orders.create(any())).thenAnswer(inv -> {
            JSONObject req = inv.getArgument(0);
            assertThat(req.getInt("amount")).isEqualTo(50_000);   // 500 points = Rs. 500
            JSONObject json = new JSONObject();
            json.put("id", "order_1");
            return new Order(json);
        });

        var res = svc.createOrder(USER, 500);

        assertThat(res.getPoints()).isEqualTo(500);
        assertThat(res.getAmount()).isEqualTo(50_000);
    }

    @Test
    void purchasesOutsideTheConfiguredBoundsAreRefused() {
        assertThatThrownBy(() -> svc.createOrder(USER, MIN - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between");
        assertThatThrownBy(() -> svc.createOrder(USER, MAX + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between");
    }

    /**
     * An account that could never HOLD points is turned away before an order exists.
     *
     * Points are retailer-only, and that rule lives in RewardPointsService — i.e. at the
     * CREDIT step, which happens after the money has been taken. A painter or customer
     * account reaching this flow therefore paid in full, had the credit rolled back by
     * the role check, and was left with no points, no purchase record and nothing to
     * refund against. Refusing at order time costs them nothing instead.
     */
    @Test
    void anAccountThatCannotHoldPointsCannotOpenAnOrderForThem() throws Exception {
        for (com.gridstore.huevista.auth.model.UserRole role : java.util.List.of(
                com.gridstore.huevista.auth.model.UserRole.CUSTOMER,
                com.gridstore.huevista.auth.model.UserRole.PAINTER,
                com.gridstore.huevista.auth.model.UserRole.DISTRIBUTOR)) {
            when(users.findById(USER)).thenReturn(java.util.Optional.of(userWithRole(role)));

            assertThatThrownBy(() -> svc.createOrder(USER, 500))
                    .as("role %s", role)
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("paint shops");
        }
        // Nothing was opened at the gateway, so there is no payment sheet to pay against.
        verify(razorpay.orders, never()).create(any());
    }

    // ── Verifying ───────────────────────────────────────────────────────────

    @Test
    void aVerifiedPaymentCreditsThePointsTheOrderWasFor() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(50_000, "points_purchase", USER, 500));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(false);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThat(svc.verifyAndCredit(USER, req())).isEqualTo(500);
            verify(points).creditPurchasedPoints(USER, 500, "pay_1");
        }
    }

    @Test
    void aBadSignatureCreditsNothing() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(false);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
            verify(points, never()).creditPurchasedPoints(anyString(), anyInt(), anyString());
        }
    }

    /**
     * The signature proves the payment is genuine, not that it was for this order. An
     * order whose notes claim more points than the amount covers must be refused — that
     * is the difference between paying Rs. 100 and walking off with 10,000 points.
     */
    @Test
    void anOrderClaimingMorePointsThanItsAmountIsRefused() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(10_000, "points_purchase", USER, 10_000));  // Rs. 100 for 10,000 pts

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
            verify(points, never()).creditPurchasedPoints(anyString(), anyInt(), anyString());
        }
    }

    @Test
    void aPaymentForSomebodyElsesOrderIsRefused() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(50_000, "points_purchase", "user-2", 500));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void aPaymentForSomeOtherKindOfOrderIsRefused() throws Exception {
        // A real payment — but for a subscription, not a points top-up.
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(50_000, "subscription", USER, 500));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    /** The signature stays valid forever, so replay is the real risk here. */
    @Test
    void aReplayedPaymentCreditsNothingASecondTime() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(50_000, "points_purchase", USER, 500));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(true);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been redeemed");
            verify(points, never()).creditPurchasedPoints(anyString(), anyInt(), anyString());
        }
    }
}
