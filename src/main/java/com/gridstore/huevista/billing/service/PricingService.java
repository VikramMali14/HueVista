package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One place that answers "what does this cost?".
 *
 * A shop buys four things: a monthly plan, points, extra projects once the plan's monthly
 * allowance is spent, and AI image credits. The extra project is the only one with two
 * prices — points or money — and both are read off the buyer's own PLAN rather than being
 * flat: the bigger the tier, the cheaper the extra, so a shop that keeps outgrowing its
 * plan is nudged up a tier instead of paying a flat premium forever. An account with no
 * paid plan (a lapsed one, or one still on the free trial) pays the FREE tier's rate, the
 * dearest.
 *
 * A CUSTOMER buys from a different list entirely — see the customer catalogue below. They
 * hold no plan, can earn no points, and the two things they can want (a room, and the
 * picture at the end of it) are sold to them at flat retail prices with a quantity against
 * each, not at a tier's rate.
 *
 * Cash amounts are in paise (Rs. 1 = 100 paise) to match Razorpay; point prices are in
 * whole points. Points are the cheaper rail by design — 80 points against ₹199 with no
 * plan — because they are bought in bulk or earned at the kiosk.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final BillingService billingService;
    private final UnbilledAccounts unbilledAccounts;
    private final com.gridstore.huevista.account.repository.OrgMembershipRepository membershipRepository;
    private final com.gridstore.huevista.auth.repository.UserRepository userRepository;

    /** Days of access a purchased project (or a reopen) opens. */
    @Value("${app.project-credit.valid-days:30}")
    private int projectValidDays;

    /**
     * What a walk-in pays at any kiosk — one flat price across the platform, whatever the
     * shop's plan is doing and whatever shop it is.
     *
     * The shop does not set this and takes no share of it: the walk-in is HueVista's own
     * customer, so the whole amount is ours for our own service. The shop is rewarded in
     * closed-loop points instead ({@link #kioskBonusPoints()}). Letting the shop
     * price the link and keep the excess is what made this a third-party collection, and
     * that is the thing this design removes.
     */
    @Value("${app.store.price-paise:19900}")
    private int kioskPricePaise;

    /** Reward points awarded to the shop whose link made the sale. */
    @Value("${app.store.bonus-points:30}")
    private int kioskBonusPoints;

    // The point price list — the only prices these things have. Every quote of one in
    // the product reads from here.
    /** Rupees per point when buying. One rupee, one point. */
    @Value("${app.points.rupees-per-point:1}")
    private int rupeesPerPoint;

    @Value("${app.points.min-purchase:100}")
    private int pointsMinPurchase;

    @Value("${app.points.max-purchase:100000}")
    private int pointsMaxPurchase;

    /** What a reopen costs in money. Flat, like its point price — a reopen buys another
     *  window on work already paid for once, so there is nothing tier-shaped about it. */
    @Value("${app.project-credit.reopen-price-paise:900}")
    private int reopenPricePaise;

    @Value("${app.points.reopen:9}")
    private int pointsPriceReopen;

    /** What reopening a CLOSED project costs — half a project, against a lapsed window's
     *  flat ₹9. See {@link #reopenPricePaise(boolean)} for why the two differ. */
    @Value("${app.project-credit.reopen-closed-price-paise:9900}")
    private int reopenClosedPricePaise;

    @Value("${app.points.reopen-closed:99}")
    private int pointsPriceReopenClosed;

    /** A second AI render on a project that already spent its included one. Flat. */
    @Value("${app.render.top-up-price-paise:9900}")
    private int renderTopUpPricePaise;

    // ── AI image credits ────────────────────────────────────────────────────
    //
    // The wallet rail for the same picture the per-project top-up above buys. One credit
    // is one AI image, and the two prices are kept deliberately equal at launch (₹198 less
    // 50% is ₹99) so a customer can never be worse off for having topped up in advance.

    /** The undiscounted price of one AI image credit, in paise. */
    @Value("${app.ai-credit.list-price-paise:19800}")
    private int aiCreditListPricePaise;

    /**
     * The launch discount on AI credits, as a whole percentage off the list price.
     *
     * A percentage rather than a second "launch price" setting, because the two would drift
     * — somebody would move the list price and leave the launch price where it was, and the
     * strike-through the customer is shown would then be a lie. Set to 0 to end the offer.
     */
    @Value("${app.ai-credit.launch-discount-percent:50}")
    private int aiCreditDiscountPercent;

    @Value("${app.ai-credit.min-purchase:1}")
    private int aiCreditMinPurchase;

    @Value("${app.ai-credit.max-purchase:50}")
    private int aiCreditMaxPurchase;

    /** Credits the plainest AI image costs — the BASIC tier, and the floor every other
     *  tier is quoted against. See {@link #aiCreditRenderCost(ProjectRender.Quality)}. */
    @Value("${app.ai-credit.render-cost:1}")
    private int aiCreditRenderCost;

    /** Credits a PRO image costs: a better model, at a bigger size. */
    @Value("${app.ai-credit.render-cost-pro:2}")
    private int aiCreditRenderCostPro;

    /** Credits a MAX image costs: the best model wired in, at the largest size. */
    @Value("${app.ai-credit.render-cost-max:4}")
    private int aiCreditRenderCostMax;

    // ── The customer catalogue ──────────────────────────────────────────────
    //
    // What a CUSTOMER buys, and the only price list in the product with a quantity beside
    // each line. Everything above this point is shop-side: a tier's rate, a points price,
    // a discount that moves with a subscription. A customer has none of those, so they are
    // sold two plain things at flat prices — a project and an AI image credit — plus the
    // combination of the two that most people actually want.
    //
    // These are FINAL prices. Plan.GST_PERCENT is 0, so what is configured here is what
    // the customer is charged; if tax is ever switched on, this list has to move with it
    // rather than being quietly grossed up, because a catalogue whose displayed price is
    // not the price paid is the one thing a shopping cart may never do.

    /** One project, bought on its own. */
    @Value("${app.customer-catalogue.project-price-paise:14900}")
    private int cataloguePricePerProject;

    /** One AI image credit, bought on its own. */
    @Value("${app.customer-catalogue.credit-price-paise:3500}")
    private int cataloguePricePerCredit;

    /** The combination: a project and the images to go with it, for less than the two
     *  bought separately (₹199 against ₹219). */
    @Value("${app.customer-catalogue.combo-price-paise:19900}")
    private int cataloguePricePerCombo;

    @Value("${app.customer-catalogue.combo-projects:1}")
    private int catalogueComboProjects;

    @Value("${app.customer-catalogue.combo-credits:2}")
    private int catalogueComboCredits;

    /**
     * How long a catalogue purchase lasts: a year, for every line on it.
     *
     * <p>It means the same thing on both lines and it is worth being precise about what:
     * a project credit opens a room that stays open for this many days, and an AI credit
     * has this many days to be spent before it lapses. One number so the cart can promise
     * one thing ("valid for a year") without that promise being true of only half of it.
     */
    @Value("${app.customer-catalogue.validity-days:365}")
    private int catalogueValidityDays;

    /** The most of any ONE line a single order may hold. A cart is not a wholesale
     *  channel, and an unbounded quantity is an unbounded Razorpay amount. */
    @Value("${app.customer-catalogue.max-quantity:20}")
    private int catalogueMaxQuantity;

    /**
     * The offers, as {@code CODE:minimum-subtotal-paise:percent-off}, cheapest first.
     *
     * <p>Configured rather than coded because an offer is the most changeable thing here —
     * and expressed as a THRESHOLD on the subtotal rather than as a per-item rule so it can
     * be stated in one line to the buyer ("₹289 and above, 10% off") and checked in one
     * line on the server.
     */
    @Value("${app.customer-catalogue.offers:HUE10:28900:10,HUE20:58900:20,HUE25:98900:25}")
    private String catalogueOffers;

    @Value("${app.points.validity-days:365}")
    private int pointsValidityDays;

    @Value("${app.points.expiry-warning-days:10}")
    private int pointsExpiryWarningDays;

    @Value("${app.project-credit.currency:INR}")
    private String currency;

    /**
     * Is this account's work covered right now?
     *
     * An administrator always is, without holding a subscription — see
     * {@link UnbilledAccounts}. Answering "no" here was what put an admin's own projects
     * behind a validity window and a reopen button.
     */
    public boolean isSubscribed(String userId) {
        return unbilledAccounts.covers(userId)
                || billingService.findEntitlingSubscription(userId).isPresent();
    }

    /**
     * What {@code points} cost in paise. The only arithmetic that converts between the
     * two units, so a change to the rate lands everywhere at once.
     */
    public int pointsPricePaise(int points) {
        return points * rupeesPerPoint * 100;
    }

    public int rupeesPerPoint() {
        return rupeesPerPoint;
    }

    public int pointsMinPurchase() {
        return pointsMinPurchase;
    }

    public int pointsMaxPurchase() {
        return pointsMaxPurchase;
    }

    public int projectValidDays() {
        return projectValidDays;
    }

    /** What one kiosk visualisation costs a walk-in. Flat across the platform. */
    public int kioskPricePaise() {
        return kioskPricePaise;
    }

    /** Reward points the shop earns per kiosk sale. */
    public int kioskBonusPoints() {
        return kioskBonusPoints;
    }

    /**
     * The plan an account buys extras at. A live PAID plan sets its own rate; anything
     * else — no plan, a lapsed one, or a free trial — pays the FREE tier's, which is the
     * dearest. A trial is deliberately excluded: it is granted, not bought, so quoting it
     * a paid tier's discount would hand out the benefit of a subscription nobody paid for.
     */
    public Plan pricingPlanFor(String userId) {
        return billingService.findEntitlingSubscription(userId)
                .filter(s -> !s.isTrial())
                .map(Subscription::getPlan)
                .orElse(Plan.FREE);
    }

    /** What one extra project costs this account in points, at its plan's rate. */
    public int pointsPriceProject(String userId) {
        return pricingPlanFor(userId).getExtraProjectPoints();
    }

    /** What one extra project costs this account in paise (GST included), at its plan's rate. */
    public int projectPricePaise(String userId) {
        return pricingPlanFor(userId).extraProjectPriceWithTaxInPaise();
    }

    /** Projects a bundle grants. */
    public static final int BUNDLE_CREDITS = 3;

    /** Projects a bundle charges for. */
    public static final int BUNDLE_PAID_FOR = 2;

    /**
     * What three projects cost bought together, in paise (GST included), at this account's
     * rate. Two projects' money for {@link #BUNDLE_CREDITS} projects.
     *
     * The discount is expressed as a smaller price for a fixed quantity rather than as a
     * free credit granted after the second purchase, because the two behave differently
     * when someone stops buying: a bundle is settled the moment it is paid for, while an
     * earn-your-third rule leaves an obligation hanging over an account that may never
     * come back. It also quotes honestly — the buyer sees ₹398 before paying it, not a
     * promise about a purchase they have not made yet.
     */
    public int bundlePricePaise(String userId) {
        return projectPricePaise(userId) * BUNDLE_PAID_FOR;
    }

    /** What one reopen costs in paise, GST included, for a project whose window lapsed. */
    public int reopenPricePaise() {
        return reopenPricePaise(false);
    }

    /**
     * What reopening this project costs in paise, GST included.
     *
     * Two different purchases wear the same name. A LAPSED project ran out of days with
     * the work unfinished, and ₹9 buys the clock back. A CLOSED one is finished — the
     * customer took their colour board and an AI render off it and said so — and reopening
     * unlocks the whole catalogue again on a job that already delivered. That is half a
     * project's worth of product, so it is priced at half a project.
     *
     * Both the order and the verify side must call this with the same project, or a
     * correctly-paid reopen fails signature verification on an amount mismatch.
     */
    public int reopenPricePaise(boolean closed) {
        int base = closed ? reopenClosedPricePaise : reopenPricePaise;
        return base * (100 + Plan.GST_PERCENT) / 100;
    }

    public int pointsPriceReopen() {
        return pointsPriceReopen(false);
    }

    /** The points twin of {@link #reopenPricePaise(boolean)}, on the same split. */
    public int pointsPriceReopen(boolean closed) {
        return closed ? pointsPriceReopenClosed : pointsPriceReopen;
    }

    /** What one more AI render on an already-rendered project costs, in paise. */
    public int renderTopUpPricePaise() {
        return renderTopUpPricePaise * (100 + Plan.GST_PERCENT) / 100;
    }

    // ── AI image credits ────────────────────────────────────────────────────

    /** What one AI image credit costs before the launch discount, in paise (GST included). */
    public int aiCreditListPricePaise() {
        return aiCreditListPricePaise * (100 + Plan.GST_PERCENT) / 100;
    }

    /** The launch discount currently on offer, as a whole percentage. 0 when it is over. */
    public int aiCreditDiscountPercent() {
        return clampPercent(aiCreditDiscountPercent);
    }

    /** What one AI image credit costs today, in paise (GST included). */
    public int aiCreditPricePaise() {
        return aiCreditPricePaise(1);
    }

    // ── The same credit, at the buyer's own rate ────────────────────────────
    //
    // A CUSTOMER buys AI credits off the catalogue (₹35, no launch offer); everyone else
    // buys them at the shop rate (₹198 less the launch discount). Two prices for one thing
    // is worth stating plainly rather than hiding: the shop's credit is bought in ones and
    // twos against a room it is already being paid to paint, and the customer's is the
    // whole of what they are buying from us.
    //
    // Every quote a buyer is shown, and every order opened for them, goes through the
    // user-aware pair below. The no-argument versions above stay for the shop-side callers
    // that have no user in hand — and because the order/verify handshake stores the rate on
    // the order itself, a buyer whose role changes mid-checkout is still verified against
    // the price they were actually charged.

    /** Whether this account buys from the customer catalogue rather than at shop rates. */
    public boolean buysFromCatalogue(String userId) {
        return userId != null && userRepository.findById(userId)
                .map(u -> u.getRole() == com.gridstore.huevista.auth.model.UserRole.CUSTOMER)
                .orElse(false);
    }

    /**
     * How long a credit this account buys is good for, or null when it never lapses.
     *
     * <p>Catalogue credits carry the catalogue's year, because that is what the cart sells
     * and says on the line. Shop credits keep the promise they were sold under — no expiry
     * at all — and nothing here shortens one already in a wallet: expiry is decided when a
     * credit is BOUGHT and stored on the batch, so a rule that changes tomorrow cannot
     * reach back and age today's purchase.
     */
    public Integer aiCreditValidityDays(String userId) {
        return buysFromCatalogue(userId) ? catalogueValidityDays() : null;
    }

    /** What one AI credit lists at for this account, in paise. */
    public int aiCreditListPricePaise(String userId) {
        return buysFromCatalogue(userId) ? cataloguePricePerCredit : aiCreditListPricePaise();
    }

    /** The discount this account's credits carry. Zero on the catalogue — its price is
     *  already the offer, and a strike-through over a price nobody ever charged is a lie. */
    public int aiCreditDiscountPercent(String userId) {
        return buysFromCatalogue(userId) ? 0 : aiCreditDiscountPercent();
    }

    /** What {@code credits} cost this account today, in paise. */
    public int aiCreditPricePaise(String userId, int credits) {
        if (!buysFromCatalogue(userId)) {
            return aiCreditPricePaise(credits);
        }
        return cataloguePricePerCredit * Math.max(0, credits);
    }

    // ── The customer catalogue ──────────────────────────────────────────────

    public int cataloguePricePerProject() {
        return cataloguePricePerProject;
    }

    public int cataloguePricePerCredit() {
        return cataloguePricePerCredit;
    }

    public int cataloguePricePerCombo() {
        return cataloguePricePerCombo;
    }

    public int catalogueComboProjects() {
        return Math.max(0, catalogueComboProjects);
    }

    public int catalogueComboCredits() {
        return Math.max(0, catalogueComboCredits);
    }

    /** Days a catalogue purchase is good for — the room it opens, and the credit it buys. */
    public int catalogueValidityDays() {
        return Math.max(1, catalogueValidityDays);
    }

    public int catalogueMaxQuantity() {
        return Math.max(1, catalogueMaxQuantity);
    }

    /**
     * One offer on the cart: a code, the subtotal it needs, and what it takes off.
     *
     * <p>{@code minSubtotalPaise} is inclusive — an order that lands exactly on ₹289 gets
     * the 10%. "₹289 and above" is how the offer reads to the buyer, and a threshold that
     * quietly meant "₹289.01 and above" would refuse the one cart somebody built on purpose
     * to reach it.
     */
    public record CatalogueOffer(String code, int minSubtotalPaise, int percentOff) {}

    /**
     * The offers on the board, weakest first.
     *
     * <p>Parsed rather than cached because it is read a handful of times per checkout and
     * a stale copy of a changed offer is worth more trouble than the parse costs. Anything
     * malformed is dropped rather than failing the request: an unreadable offer should cost
     * a discount, never a sale.
     */
    public java.util.List<CatalogueOffer> catalogueOffers() {
        if (catalogueOffers == null || catalogueOffers.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(catalogueOffers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(PricingService::parseOffer)
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(CatalogueOffer::minSubtotalPaise))
                .toList();
    }

    private static CatalogueOffer parseOffer(String spec) {
        String[] parts = spec.split(":");
        if (parts.length != 3) return null;
        try {
            String code = parts[0].trim().toUpperCase(java.util.Locale.ROOT);
            int min = Integer.parseInt(parts[1].trim());
            int percent = clampPercent(Integer.parseInt(parts[2].trim()));
            return code.isEmpty() || min < 0 || percent <= 0 ? null
                    : new CatalogueOffer(code, min, percent);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * The best offer this subtotal has earned, or empty when it has earned none.
     *
     * <p>"Best" is the largest percentage, not the highest threshold, so an offer list
     * someone edits into a non-monotonic state still gives the buyer the better of the two
     * they qualify for rather than whichever happened to be last in the string.
     */
    public java.util.Optional<CatalogueOffer> bestOfferFor(int subtotalPaise) {
        return catalogueOffers().stream()
                .filter(o -> subtotalPaise >= o.minSubtotalPaise())
                .max(java.util.Comparator.comparingInt(CatalogueOffer::percentOff));
    }

    /** The offer with this code, if it exists and this subtotal qualifies for it. */
    public java.util.Optional<CatalogueOffer> offerFor(String code, int subtotalPaise) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        String wanted = code.trim().toUpperCase(java.util.Locale.ROOT);
        return catalogueOffers().stream()
                .filter(o -> o.code().equals(wanted))
                .filter(o -> subtotalPaise >= o.minSubtotalPaise())
                .findFirst();
    }

    /**
     * What comes off a subtotal at {@code percentOff}, in paise.
     *
     * <p>Rounded DOWN, so the discount is never a paisa more than the percentage says and
     * the total can never be quoted below what the order is opened at. Both the cart quote
     * and the verification of a paid order run through this one line, because a rounding
     * difference between them would reject a correctly-paid order by a rupee.
     */
    public static int discountPaise(int subtotalPaise, int percentOff) {
        if (subtotalPaise <= 0 || percentOff <= 0) return 0;
        return (int) ((long) subtotalPaise * clampPercent(percentOff) / 100);
    }

    /**
     * What {@code credits} AI image credits cost today, in paise (GST included).
     *
     * The discount is applied to the WHOLE order rather than per credit and then multiplied,
     * so a rate that does not divide evenly cannot lose a rupee per credit to integer
     * truncation — at ten credits that is the difference between the price quoted on the
     * button and the price the order was created at, and verification refuses on exactly
     * that mismatch.
     *
     * <p>Both the order and the verify side call this, or a correctly-paid purchase fails
     * signature verification on an amount mismatch.
     */
    public int aiCreditPricePaise(int credits) {
        long gross = (long) aiCreditListPricePaise() * Math.max(0, credits);
        return (int) (gross * (100 - aiCreditDiscountPercent()) / 100);
    }

    public int aiCreditMinPurchase() {
        return Math.max(1, aiCreditMinPurchase);
    }

    public int aiCreditMaxPurchase() {
        return Math.max(aiCreditMinPurchase(), aiCreditMaxPurchase);
    }

    /** Credits the plainest AI image costs. */
    public int aiCreditRenderCost() {
        return Math.max(1, aiCreditRenderCost);
    }

    /**
     * Credits one AI image costs at {@code quality}.
     *
     * <p>Three tiers, and the ratio between them is the point: a BASIC image is one credit,
     * a PRO two and a MAX four. They are different MODELS at different sizes (see
     * {@code ProjectRenderWorker}), so the price difference is a real cost difference and
     * not a fence — which is why the cheapest tier is a whole product on its own rather
     * than a deliberately poor one.
     *
     * <p>Null reads as BASIC. Every render made before the tiers existed was one, and a
     * client that names no quality is asking for the ordinary picture.
     */
    public int aiCreditRenderCost(com.gridstore.huevista.project.model.ProjectRender.Quality quality) {
        if (quality == null) return aiCreditRenderCost();
        return switch (quality) {
            case BASIC -> aiCreditRenderCost();
            case PRO -> Math.max(aiCreditRenderCost(), aiCreditRenderCostPro);
            case MAX -> Math.max(aiCreditRenderCost(), aiCreditRenderCostMax);
        };
    }

    /** A misconfigured discount must never make an order free, or negative. */
    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public int pointsValidityDays() {
        return pointsValidityDays;
    }

    public int pointsExpiryWarningDays() {
        return pointsExpiryWarningDays;
    }

    /**
     * The account a shop is billed through, and the one its reward points land in. A shop
     * is billed through its OWNER everywhere else in the product, so points follow the
     * same account rather than inventing a second notion of "the shop's money".
     */
    public java.util.Optional<String> shopOwnerUserId(String orgId) {
        return membershipRepository
                .findUserIdsByOrganizationIdAndRole(
                        orgId, com.gridstore.huevista.account.model.OrgMemberRole.OWNER)
                .stream().findFirst();
    }

    /**
     * Is this shop covered by a plan? Asked of the owner account, per
     * {@link #shopOwnerUserId}. A shop with no owner account reads as unsubscribed — the
     * safe direction, since the alternative is giving away a subscribed rate to an org
     * nobody can be billed for.
     */
    public boolean isShopSubscribed(String orgId) {
        return shopOwnerUserId(orgId).map(this::isSubscribed).orElse(false);
    }

    public String currency() {
        return currency;
    }
}
