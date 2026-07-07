package com.gridstore.huevista.store;

import com.gridstore.huevista.account.dto.GuestRedeemResponse;
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

import java.time.Instant;
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
 * Verifies the kiosk money path: a verified payment buys exactly one access
 * code with the correct platform/retailer split, a replayed payment re-issues
 * the SAME code (kiosk customers must never lose what they paid for), and a
 * bad signature or a payment for some other order issues nothing.
 */
class StoreKioskServiceTest {

    private static final int MIN_PRICE = 5000;   // Rs.50 platform base
    private static final int LINK_PRICE = 7900;  // Rs.79 kiosk price

    private final Organization org = Organization.builder().id("org-1").name("Mehta Paints").build();
    private final StoreLink link = StoreLink.builder()
            .id("link-1").organization(org).slug("mehta-x7k2p9").pricePaise(LINK_PRICE).validDays(3).build();

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

    private static GuestRedeemResponse guest(String code) {
        return GuestRedeemResponse.builder()
                .guestToken("guest-token").code(code).shopName("Mehta Paints")
                .validDays(3).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private StoreKioskService service(RazorpayClient razorpay, StoreLinkRepository links,
                                      StorePaymentRepository payments, AccessCodeService codes) {
        StoreKioskService svc = new StoreKioskService(razorpay, links, payments, codes);
        ReflectionTestUtils.setField(svc, "keyId", "key");
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
        ReflectionTestUtils.setField(svc, "minPricePaise", MIN_PRICE);
        ReflectionTestUtils.setField(svc, "currency", "INR");
        return svc;
    }

    @Test
    void verifiedPaymentIssuesCodeWithCorrectSplit() throws Exception {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        when(razorpay.orders.fetch("order_1")).thenReturn(order(LINK_PRICE, "store_kiosk", "link-1"));
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlug("mehta-x7k2p9")).thenReturn(Optional.of(link));
        StorePaymentRepository payments = mock(StorePaymentRepository.class);
        when(payments.findByPaymentId("pay_1")).thenReturn(Optional.empty());
        when(payments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        AccessCodeService codes = mock(AccessCodeService.class);
        CustomerAccessCode code = CustomerAccessCode.builder().id("code-1").code("ABCD2345").organization(org).build();
        when(codes.issueForStore(org, 3)).thenReturn(code);
        when(codes.redeemAsGuest("ABCD2345")).thenReturn(guest("ABCD2345"));
        StoreKioskService svc = service(razorpay, links, payments, codes);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            StoreCheckoutResponse res = svc.verifyAndIssue("mehta-x7k2p9", req("order_1", "pay_1"));

            assertThat(res.getCode()).isEqualTo("ABCD2345");
            assertThat(res.getGuestToken()).isEqualTo("guest-token");
            assertThat(res.getAmountPaise()).isEqualTo(LINK_PRICE);

            ArgumentCaptor<StorePayment> saved = ArgumentCaptor.forClass(StorePayment.class);
            verify(payments).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getAmountPaise()).isEqualTo(LINK_PRICE);
            assertThat(saved.getValue().getPlatformFeePaise()).isEqualTo(MIN_PRICE);
            // The excess over the Rs.50 base is the retailer's wallet share.
            assertThat(saved.getValue().getRetailerSharePaise()).isEqualTo(LINK_PRICE - MIN_PRICE);
        }
    }

    @Test
    void replayedPaymentReturnsSameCodeWithoutIssuingAnother() throws Exception {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlug("mehta-x7k2p9")).thenReturn(Optional.of(link));
        StorePaymentRepository payments = mock(StorePaymentRepository.class);
        CustomerAccessCode code = CustomerAccessCode.builder().id("code-1").code("ABCD2345").organization(org).build();
        StorePayment prior = StorePayment.builder()
                .storeLink(link).organization(org).paymentId("pay_1").orderId("order_1")
                .amountPaise(LINK_PRICE).platformFeePaise(MIN_PRICE)
                .retailerSharePaise(LINK_PRICE - MIN_PRICE).accessCode(code).build();
        when(payments.findByPaymentId("pay_1")).thenReturn(Optional.of(prior));
        AccessCodeService codes = mock(AccessCodeService.class);
        when(codes.redeemAsGuest("ABCD2345")).thenReturn(guest("ABCD2345"));
        StoreKioskService svc = service(razorpay, links, payments, codes);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            StoreCheckoutResponse res = svc.verifyAndIssue("mehta-x7k2p9", req("order_1", "pay_1"));

            // Same code back, no second code minted, no second payment row.
            assertThat(res.getCode()).isEqualTo("ABCD2345");
            verify(codes, never()).issueForStore(any(), anyInt());
            verify(payments, never()).saveAndFlush(any());
        }
    }

    @Test
    void invalidSignatureIssuesNothing() {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlug("mehta-x7k2p9")).thenReturn(Optional.of(link));
        StorePaymentRepository payments = mock(StorePaymentRepository.class);
        AccessCodeService codes = mock(AccessCodeService.class);
        StoreKioskService svc = service(razorpay, links, payments, codes);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(false);

            assertThatThrownBy(() -> svc.verifyAndIssue("mehta-x7k2p9", req("order_1", "pay_1")))
                    .isInstanceOf(SecurityException.class);
            verify(payments, never()).saveAndFlush(any());
            verify(codes, never()).redeemAsGuest(anyString());
        }
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
            verify(codes, never()).issueForStore(any(), anyInt());
        }
    }

    @Test
    void pausedLinkRefusesNewOrders() {
        StoreLink paused = StoreLink.builder()
                .id("link-1").organization(org).slug("mehta-x7k2p9").pricePaise(LINK_PRICE)
                .validDays(3).active(false).build();
        StoreLinkRepository links = mock(StoreLinkRepository.class);
        when(links.findBySlug("mehta-x7k2p9")).thenReturn(Optional.of(paused));
        StoreKioskService svc = service(mock(RazorpayClient.class), links,
                mock(StorePaymentRepository.class), mock(AccessCodeService.class));

        assertThatThrownBy(() -> svc.createOrder("mehta-x7k2p9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paused");
    }
}
