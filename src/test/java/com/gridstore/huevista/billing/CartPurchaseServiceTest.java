package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.dto.CreateCartOrderRequest;
import com.gridstore.huevista.billing.dto.VerifyCartPurchaseRequest;
import com.gridstore.huevista.billing.repository.CartPurchaseRepository;
import com.gridstore.huevista.billing.service.AiCreditService;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.CartPurchaseService;
import com.gridstore.huevista.billing.service.PaymentAttemptService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.ProjectCreditService;
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
 * The customer's basket, where three things have to hold at once: the client names
 * quantities and never money, the offer is worked out from the subtotal rather than
 * accepted from the browser, and one payment is redeemed exactly once.
 *
 * <p>The amount is the interesting part. A cart multiplies a client-supplied count by a
 * price, which is precisely the input a client should not be able to invent — and unlike
 * every other purchase in the product this one carries a DISCOUNT, so there are two ways to
 * be wrong instead of one.
 */
class CartPurchaseServiceTest {

    private static final String USER = "cust-1";

    // The catalogue under test: ₹149 a project, ₹35 a credit, ₹199 for one project and two
    // credits, and the three offers on the board.
    private static final int PROJECT_PRICE = 14_900;
    private static final int CREDIT_PRICE = 3_500;
    private static final int COMBO_PRICE = 19_900;
    private static final int VALID_DAYS = 365;

    private RazorpayClient razorpay;
    private CartPurchaseRepository purchases;
    private ProjectCreditService projects;
    private AiCreditService credits;
    private PricingService pricing;
    private com.gridstore.huevista.auth.repository.UserRepository users;
    private CartPurchaseService svc;

    @BeforeEach
    void setUp() throws Exception {
        razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        purchases = mock(CartPurchaseRepository.class);
        projects = mock(ProjectCreditService.class);
        credits = mock(AiCreditService.class);
        users = mock(com.gridstore.huevista.auth.repository.UserRepository.class);
        // The buyer is a customer unless a test says otherwise — this counter is theirs.
        when(users.findById(USER)).thenReturn(java.util.Optional.of(
                userWithRole(com.gridstore.huevista.auth.model.UserRole.CUSTOMER)));

        pricing = new PricingService(mock(BillingService.class),
                mock(com.gridstore.huevista.billing.service.UnbilledAccounts.class),
                mock(OrgMembershipRepository.class), users);
        ReflectionTestUtils.setField(pricing, "cataloguePricePerProject", PROJECT_PRICE);
        ReflectionTestUtils.setField(pricing, "cataloguePricePerCredit", CREDIT_PRICE);
        ReflectionTestUtils.setField(pricing, "cataloguePricePerCombo", COMBO_PRICE);
        ReflectionTestUtils.setField(pricing, "catalogueComboProjects", 1);
        ReflectionTestUtils.setField(pricing, "catalogueComboCredits", 2);
        ReflectionTestUtils.setField(pricing, "catalogueValidityDays", VALID_DAYS);
        ReflectionTestUtils.setField(pricing, "catalogueMaxQuantity", 20);
        ReflectionTestUtils.setField(pricing, "catalogueOffers",
                "HUE10:28900:10,HUE20:58900:20,HUE25:98900:25");
        ReflectionTestUtils.setField(pricing, "currency", "INR");

        svc = new CartPurchaseService(razorpay, purchases, projects, credits, pricing,
                mock(PaymentAttemptService.class));
        ReflectionTestUtils.setField(svc, "keyId", "rzp_key");
        ReflectionTestUtils.setField(svc, "keySecret", "secret");

        when(razorpay.orders.create(any())).thenAnswer(inv -> {
            JSONObject json = new JSONObject();
            json.put("id", "order_1");
            return new Order(json);
        });
    }

    private static com.gridstore.huevista.auth.model.User userWithRole(
            com.gridstore.huevista.auth.model.UserRole role) {
        com.gridstore.huevista.auth.model.User u = new com.gridstore.huevista.auth.model.User();
        u.setId(USER);
        u.setRole(role);
        return u;
    }

    private static CreateCartOrderRequest basket(int projects, int credits, int combos, String code) {
        CreateCartOrderRequest r = new CreateCartOrderRequest();
        r.setProjects(projects);
        r.setCredits(credits);
        r.setCombos(combos);
        r.setDiscountCode(code);
        return r;
    }

    private static VerifyCartPurchaseRequest req() {
        VerifyCartPurchaseRequest r = new VerifyCartPurchaseRequest();
        r.setOrderId("order_1");
        r.setPaymentId("pay_1");
        r.setSignature("sig");
        return r;
    }

    /**
     * An order as Razorpay hands it back, with the notes the service wrote onto it — and
     * deliberately WITHOUT a discount base, which is how every order opened before packages
     * left the offer looks. {@link #paidOrderWithBase} is the one that carries it.
     */
    private static Order paidOrder(int amountPaise, String purpose, String userId,
                                   int projectQty, int creditQty, int comboQty,
                                   int discountPercent) {
        JSONObject json = new JSONObject();
        json.put("amount", amountPaise);
        JSONObject notes = new JSONObject();
        notes.put("purpose", purpose);
        notes.put("userId", userId);
        notes.put("projectQty", projectQty);
        notes.put("creditQty", creditQty);
        notes.put("comboQty", comboQty);
        notes.put("projectPrice", PROJECT_PRICE);
        notes.put("creditPrice", CREDIT_PRICE);
        notes.put("comboPrice", COMBO_PRICE);
        notes.put("comboProjects", 1);
        notes.put("comboCredits", 2);
        notes.put("discountPercent", discountPercent);
        notes.put("discountCode", discountPercent > 0 ? "HUE10" : "");
        notes.put("validDays", VALID_DAYS);
        json.put("notes", notes);
        return new Order(json);
    }

    /** The same order with the base the percentage was struck on written onto it. */
    private static Order paidOrderWithBase(int amountPaise, int projectQty, int creditQty,
                                           int comboQty, int discountPercent, int discountBase) {
        Order order = paidOrder(amountPaise, "cart_purchase", USER,
                projectQty, creditQty, comboQty, discountPercent);
        JSONObject notes = order.get("notes");
        notes.put("discountBase", discountBase);
        return order;
    }

    // ── Pricing ─────────────────────────────────────────────────────────────

    @Test
    void theAmountIsAddedUpFromTheCatalogueAndNeverFromTheClient() {
        // One project and two credits, bought as separate lines: ₹149 + ₹70.
        var res = svc.createOrder(USER, basket(1, 2, 0, null));

        assertThat(res.getSubtotalPaise()).isEqualTo(PROJECT_PRICE + 2 * CREDIT_PRICE);
        assertThat(res.getAmountPaise()).isEqualTo(21_900);
        assertThat(res.getDiscountPercent()).isZero();
        assertThat(res.getProjectsGranted()).isEqualTo(1);
        assertThat(res.getCreditsGranted()).isEqualTo(2);
        assertThat(res.getValidDays()).isEqualTo(VALID_DAYS);
    }

    /** A combo is one line and two kinds of thing — and it is cheaper than its parts. */
    @Test
    void aComboGrantsWhatIsInIt() {
        var res = svc.createOrder(USER, basket(0, 0, 2, null));

        assertThat(res.getSubtotalPaise()).isEqualTo(2 * COMBO_PRICE);
        assertThat(res.getProjectsGranted()).isEqualTo(2);
        assertThat(res.getCreditsGranted()).isEqualTo(4);
        // Two combos beat two projects and four credits bought separately.
        assertThat(res.getSubtotalPaise())
                .isLessThan(2 * PROJECT_PRICE + 4 * CREDIT_PRICE);
    }

    @Test
    void anEmptyBasketIsRefusedRatherThanPricedAtZero() {
        assertThatThrownBy(() -> svc.createOrder(USER, basket(0, 0, 0, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aQuantityBeyondTheLineLimitIsRefused() {
        // The one client-supplied number the server multiplies by a price. Unbounded here
        // is an unbounded Razorpay amount.
        assertThatThrownBy(() -> svc.createOrder(USER, basket(21, 0, 0, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("up to 20");
    }

    // ── Offers ──────────────────────────────────────────────────────────────

    @Test
    void theBestEarnedOfferIsAppliedEvenWhenNoCodeIsSent() {
        // Two projects — ₹298, past ₹289 and short of ₹589. Nobody typed anything.
        var res = svc.createOrder(USER, basket(2, 0, 0, null));

        assertThat(res.getDiscountCode()).isEqualTo("HUE10");
        assertThat(res.getDiscountPercent()).isEqualTo(10);
        assertThat(res.getDiscountPaise()).isEqualTo(2_980);
        assertThat(res.getAmountPaise()).isEqualTo(29_800 - 2_980);
    }

    @Test
    void aCodeTheBasketHasNotEarnedTakesNothingOffBeyondWhatItHas() {
        // ₹199 in the basket, and a 25% code typed at it. The subtotal has earned no offer
        // at all, so the code is worth exactly nothing — this is the case a client could
        // otherwise claim a quarter off with.
        var res = svc.createOrder(USER, basket(0, 0, 1, "HUE25"));

        assertThat(res.getDiscountPercent()).isZero();
        assertThat(res.getAmountPaise()).isEqualTo(COMBO_PRICE);
    }

    @Test
    void aCodeIsHonouredWhenTheBasketHasEarnedIt() {
        // Seven projects — ₹1,043, every offer unlocked. The buyer picked the weakest one,
        // and picking between earned offers is the whole point of the strip.
        var res = svc.createOrder(USER, basket(7, 0, 0, "HUE10"));

        assertThat(res.getDiscountPercent()).isEqualTo(10);
        assertThat(res.getAmountPaise()).isEqualTo(104_300 - 10_430);
    }

    // ── Packages against percentages ────────────────────────────────────────
    //
    // The combo and the bundle carry their saving in their own price. A percentage on top
    // of that is one basket discounted twice at a rate nobody set, so they neither earn an
    // offer nor receive one — and the two have to move together, or a basket earns a
    // discount of nothing and reads as broken.

    @Test
    void aBasketOfNothingButPackagesEarnsNoPercentageOnTop() {
        // ₹995 of combos. Under the old rule this took 25% off a line already sold below
        // the price of its parts.
        var res = svc.createOrder(USER, basket(0, 0, 5, null));

        assertThat(res.getDiscountPercent()).isZero();
        assertThat(res.getDiscountPaise()).isZero();
        assertThat(res.getAmountPaise()).isEqualTo(5 * COMBO_PRICE);
    }

    @Test
    void aPackageInTheBasketNeitherEarnsTheOfferNorIsDiscountedByIt() {
        // Two projects (₹298, enough for HUE10 on their own) and a combo alongside them.
        // The 10% comes off ₹298 and not off ₹497 — the combo is rung up at its ticket
        // price and does not lift the basket into a better band either.
        var res = svc.createOrder(USER, basket(2, 0, 1, null));

        assertThat(res.getSubtotalPaise()).isEqualTo(2 * PROJECT_PRICE + COMBO_PRICE);
        assertThat(res.getDiscountPercent()).isEqualTo(10);
        assertThat(res.getDiscountPaise()).isEqualTo(2_980);
        assertThat(res.getAmountPaise()).isEqualTo(2 * PROJECT_PRICE + COMBO_PRICE - 2_980);
    }

    @Test
    void packagesCanBeBroughtBackIntoTheOfferForACampaign() {
        // The rule is a switch, not a weld. Flipped on, the whole subtotal earns and
        // receives again — exactly what the counter did before.
        ReflectionTestUtils.setField(pricing, "catalogueOffersApplyToPackages", true);

        var res = svc.createOrder(USER, basket(0, 0, 2, null));

        assertThat(res.getDiscountPercent()).isEqualTo(10);
        assertThat(res.getAmountPaise()).isEqualTo(39_800 - 3_980);
    }

    @Test
    void offersAreCheckedAtTheirThresholdInclusively() {
        // "₹289 and above" is what the buyer is told, and a basket built on purpose to
        // land exactly on it must not be refused by a paisa.
        assertThat(PricingService.discountPaise(28_900, 10)).isEqualTo(2_890);
        assertThat(pricing.bestOfferFor(28_900)).isPresent();
        assertThat(pricing.bestOfferFor(28_899)).isEmpty();
    }

    /** The discount rounds DOWN, so the total can never be quoted below the order. */
    @Test
    void theDiscountNeverRoundsInTheHouseSFavour() {
        // ₹398 less 10% is ₹39.80 exactly; ₹995 less 25% is ₹248.75. Both are whole paise,
        // so the interesting case is one that is not: ₹289 less 20% = ₹57.80.
        assertThat(PricingService.discountPaise(39_800, 10)).isEqualTo(3_980);
        assertThat(PricingService.discountPaise(99_500, 25)).isEqualTo(24_875);
        assertThat(PricingService.discountPaise(3_333, 10)).isEqualTo(333);
    }

    // ── Who may buy ─────────────────────────────────────────────────────────

    @Test
    void anAccountThisCounterIsNotForCannotOpenAnOrderAtIt() throws Exception {
        // A shop's prices move with its plan and its projects land on that plan's
        // allowance. Quoting it retail would sell it the wrong thing at the wrong price —
        // and finding that out after the payment sheet has closed is too late.
        for (com.gridstore.huevista.auth.model.UserRole role : java.util.List.of(
                com.gridstore.huevista.auth.model.UserRole.RETAILER,
                com.gridstore.huevista.auth.model.UserRole.PAINTER,
                com.gridstore.huevista.auth.model.UserRole.DISTRIBUTOR)) {
            when(users.findById(USER)).thenReturn(java.util.Optional.of(userWithRole(role)));

            assertThatThrownBy(() -> svc.createOrder(USER, basket(1, 0, 0, null)))
                    .as("role %s", role)
                    .isInstanceOf(SecurityException.class);
        }
        verify(razorpay.orders, never()).create(any());
    }

    // ── Verifying ───────────────────────────────────────────────────────────

    @Test
    void aVerifiedPaymentHandsOverWhatTheOrderWasFor() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(35_820, "cart_purchase", USER, 0, 0, 2, 10));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(false);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            svc.verifyAndCredit(USER, req());

            // Two combos: two projects and four credits, both dated a year.
            verify(projects).creditCatalogueProjects(USER, 2, VALID_DAYS);
            verify(credits).creditPurchased(USER, 4, "pay_1", VALID_DAYS);
        }
    }

    @Test
    void aBadSignatureHandsOverNothing() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(false);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
            verify(projects, never()).creditCatalogueProjects(anyString(), anyInt(), anyInt());
            verify(credits, never()).creditPurchased(anyString(), anyInt(), anyString(), any());
        }
    }

    /**
     * A valid signature proves the payment is genuine, not that it was for this basket.
     * An order whose notes claim more than the amount covers is the difference between
     * paying ₹199 and walking off with five projects.
     */
    @Test
    void anOrderClaimingMoreThanItsAmountCoversIsRefused() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(19_900, "cart_purchase", USER, 5, 0, 0, 0)); // ₹199 for 5

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
            verify(projects, never()).creditCatalogueProjects(anyString(), anyInt(), anyInt());
        }
    }

    /** Another account's basket cannot be redeemed into this one. */
    @Test
    void anOrderOpenedByAnotherAccountIsRefused() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(19_900, "cart_purchase", "someone-else", 0, 0, 1, 0));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    /** An order for some OTHER flow cannot be spent here. */
    @Test
    void anOrderFromAnotherFlowIsRefused() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(19_900, "points_purchase", USER, 0, 0, 1, 0));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    /** A Checkout signature stays valid for ever, so one payment must redeem once. */
    @Test
    void aPaymentCanOnlyBeRedeemedOnce() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(19_900, "cart_purchase", USER, 0, 0, 1, 0));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(true);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been redeemed");
            verify(projects, never()).creditCatalogueProjects(anyString(), anyInt(), anyInt());
        }
    }

    /**
     * An order opened before packages left the offer must still redeem.
     *
     * <p>Its notes carry no discount base, because there was none to carry — the whole
     * subtotal was the base. Reading the new, narrower rule into it would work out a
     * different amount from the one Razorpay actually took and refuse a payment the buyer
     * has already made. {@link #paidOrder} writes no base, which is the case exactly.
     */
    @Test
    void anOrderPricedBeforeTheRuleChangedStillRedeems() throws Exception {
        // Two combos at ₹398 less the 10% they earned under the old rule = ₹358.20.
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(35_820, "cart_purchase", USER, 0, 0, 2, 10));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(false);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            svc.verifyAndCredit(USER, req());

            verify(projects).creditCatalogueProjects(USER, 2, VALID_DAYS);
            verify(credits).creditPurchased(USER, 4, "pay_1", VALID_DAYS);
        }
    }

    /**
     * A basket priced under the new rule redeems for exactly what it granted.
     *
     * <p>Two projects and a combo: the 10% came off the ₹298 of singles and not off the
     * ₹497 total, and verification has to reach the same ₹467.02 from the notes alone or
     * the buyer is charged correctly and handed nothing.
     */
    @Test
    void aBasketPricedWithPackagesOutsideTheOfferRedeemsForWhatItGranted() throws Exception {
        int subtotal = 2 * PROJECT_PRICE + COMBO_PRICE;
        int amount = subtotal - 2_980;
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrderWithBase(amount, 2, 0, 1, 10, 2 * PROJECT_PRICE));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(false);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            svc.verifyAndCredit(USER, req());

            // Two bought outright plus the combo's one; the combo's two credits.
            verify(projects).creditCatalogueProjects(USER, 3, VALID_DAYS);
            verify(credits).creditPurchased(USER, 2, "pay_1", VALID_DAYS);
        }
    }

    /** A base bigger than the basket it is struck on is an invented discount. */
    @Test
    void anOrderClaimingADiscountBaseBeyondItsSubtotalIsRefused() throws Exception {
        int subtotal = COMBO_PRICE;
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrderWithBase(subtotal - 9_950, 0, 0, 1, 10, 99_500));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndCredit(USER, req()))
                    .isInstanceOf(SecurityException.class);
            verify(projects, never()).creditCatalogueProjects(anyString(), anyInt(), anyInt());
        }
    }

    /**
     * The amount is checked against the rate the ORDER was opened at, not against today's.
     *
     * An offer that ends, or a price that moves, between paying and coming back must not
     * fail verification on money the buyer has already handed over.
     */
    @Test
    void anOrderIsVerifiedAgainstTheRateItWasOpenedAt() throws Exception {
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(paidOrder(35_820, "cart_purchase", USER, 0, 0, 2, 10));
        when(purchases.existsByPaymentId("pay_1")).thenReturn(false);
        // The offer is withdrawn while the buyer is in Checkout.
        ReflectionTestUtils.setField(pricing, "catalogueOffers", "");

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any())).thenReturn(true);

            svc.verifyAndCredit(USER, req());

            verify(projects).creditCatalogueProjects(USER, 2, VALID_DAYS);
            verify(credits).creditPurchased(USER, 4, "pay_1", VALID_DAYS);
        }
    }
}
