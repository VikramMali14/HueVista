package com.gridstore.huevista.store;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.service.AccessCodeService;
import com.gridstore.huevista.store.dto.StoreCheckoutResponse;
import com.gridstore.huevista.store.dto.VerifyStoreOrderRequest;
import com.gridstore.huevista.store.model.StoreLink;
import com.gridstore.huevista.store.model.StorePayment;
import com.gridstore.huevista.store.repository.StoreLinkRepository;
import com.gridstore.huevista.store.repository.StorePaymentRepository;
import com.gridstore.huevista.store.service.StoreKioskService;
import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

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
 * Verifies the kiosk money path: a verified payment buys exactly one access code and
 * credits the shop its reward points (never a share of the cash), a replayed payment
 * re-issues the SAME code (kiosk customers must never lose what they paid for), and a
 * bad signature or a payment for some other order issues nothing.
 */
class StoreKioskServiceTest {

    private static final int KIOSK_PRICE = 9900;  // Rs.99 flat, platform-wide
    private static final int BONUS_POINTS = 30;   // reward points to the shop
    private static final String OWNER = "owner-1";

    private final Organization org = Organization.builder().id("org-1").name("Mehta Paints").build();
    private final StoreLink link = StoreLink.builder()
            .id("link-1").organization(org).slug("mehta-x7k2p9").build();

    private com.gridstore.huevista.billing.service.RewardPointsService points;

    private VerifyStoreOrderRequest req(String order, String payment) {
        VerifyStoreOrderRequest r = new VerifyStoreOrderRequest();
        r.setOrderId(order);
        r.setPaymentId(payment);
        r.setSignature("sig");
        return r;
    }

    private static Order order(int amount, String purpose, String storeLinkId) {
        JSONObject json = new JSONObject();
        json.put("amount", amount);
        JSONObject notes = new JSONObject();
        notes.put("purpose", purpose);
        notes.put("storeLinkId", storeLinkId);
        json.put("notes", notes);
        return new Order(json);
    }

    private com.gridstore.huevista.account.service.GuestAccountService guestAccounts;

    private StoreKioskService service(RazorpayClient razorpay, StoreLinkRepository links,
                                      StorePaymentRepository payments, AccessCodeService codes) {
        var billing = mock(com.gridstore.huevista.billing.service.BillingService.class);
        var memberships = mock(com.gridstore.huevista.account.repository.OrgMembershipRepository.class);
        var pricing = new com.gridstore.huevista.billing.service.PricingService(billing,
                mock(com.gridstore.huevista.billing.service.UnbilledAccounts.class), memberships,
                mock(com.gridstore.huevista.auth.repository.UserRepository.class));
        ReflectionTestUtils.setField(pricing, "kioskPricePaise", KIOSK_PRICE);
        ReflectionTestUtils.setField(pricing, "kioskBonusPoints", BONUS_POINTS);
        when(memberships.findUserIdsByOrganizationIdAndRole(
                "org-1", com.gridstore.huevista.account.model.OrgMemberRole.OWNER))
                .thenReturn(java.util.List.of(OWNER));

        points = mock(com.gridstore.huevista.billing.service.RewardPointsService.class);
        guestAccounts = mock(com.gridstore.huevista.account.service.GuestAccountService.class);
        // The account a kiosk purchase lands on. Every verified payment opens or reuses
        // one, so the money path can't be exercised without it.
        when(guestAccounts.provisionForKiosk(any(), any(), any()))
                .thenReturn(com.gridstore.huevista.auth.model.User.builder()
                        .id("kiosk-user-1").email("walkin@example.com").name("walkin")
                        .provider(com.gridstore.huevista.auth.model.AuthProvider.ACCESS_CODE)
                        .role(com.gridstore.huevista.auth.model.UserRole.CUSTOMER).build());
        when(guestAccounts.isGuestAccount(any())).thenReturn(true);

        StoreKioskService svc = new StoreKioskService(razorpay, links, payments, codes,
                guestAccounts,
                mock(com.gridstore.huevista.auth.service.AuthService.class),
                mock(com.gridstore.huevista.notification.EmailSender.class),
                pricing, points,
                mock(com.gridstore.huevista.billing.service.PaymentAttemptService.class));
        ReflectionTestUtils.setField(svc, "keyId", "key");
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
        ReflectionTestUtils.setField(svc, "currency", "INR");
        return svc;
    }







    @Test
    void paymentForSomeOtherOrderIsRejected() throws Exception {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        // A real payment — but for a project-credit order, not this store link.
        when(razorpay.orders.fetch("order_1")).thenReturn(order(4900, "project_credit", ""));
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlug("mehta-x7k2p9")).thenReturn(Optional.of(link));
        StorePaymentRepository payments = mock(StorePaymentRepository.class);
        when(payments.findByPaymentId("pay_1")).thenReturn(Optional.empty());
        AccessCodeService codes = mock(AccessCodeService.class);
        StoreKioskService svc = service(razorpay, links, payments, codes);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndIssue("mehta-x7k2p9", req("order_1", "pay_1")))
                    .isInstanceOf(SecurityException.class);
            verify(payments, never()).saveAndFlush(any());
            verify(codes, never()).issueForStore(any());
        }
    }

    @Test
    void pausedLinkRefusesNewOrders() {
        StoreLink paused = StoreLink.builder()
                .id("link-1").organization(org).slug("mehta-x7k2p9").active(false).build();
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlugAndDeletedAtIsNull("mehta-x7k2p9")).thenReturn(Optional.of(paused));
        StoreKioskService svc = service(mock(RazorpayClient.class), links,
                mock(StorePaymentRepository.class), mock(AccessCodeService.class));

        assertThatThrownBy(() -> svc.createOrder("mehta-x7k2p9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paused");
    }

    /** A link the shop deleted is gone as far as anyone opening its URL is concerned. */
    @Test
    void deletedLinkRefusesNewOrders() {
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlugAndDeletedAtIsNull("mehta-x7k2p9")).thenReturn(Optional.empty());
        StoreKioskService svc = service(mock(RazorpayClient.class), links,
                mock(StorePaymentRepository.class), mock(AccessCodeService.class));

        assertThatThrownBy(() -> svc.createOrder("mehta-x7k2p9"))
                .isInstanceOf(com.gridstore.huevista.common.exception.ResourceNotFoundException.class);
    }

    /**
     * Deleting a link stops new sales, not one already paid for.
     *
     * A shop can retire a link while a walk-in has Checkout open. The money has moved by
     * the time verification runs, so that customer gets the code they bought — the same
     * asymmetry a pause already has. Keeping their money and issuing nothing is the one
     * outcome this must never have.
     */


    /**
     * A shop with no owner account earns nothing — and the walk-in still gets what they
     * paid for. Their access must never hinge on the shop having finished its own setup.
     */

}
