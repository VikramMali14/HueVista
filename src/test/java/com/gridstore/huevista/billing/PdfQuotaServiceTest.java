package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.PdfQuotaService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Colour-board PDF quota: a retailer spends their own plan, a guest spends the issuing
 * shop's, a CUSTOMER spends no monthly counter at all, and reservation is the atomic
 * conditional UPDATE (0 rows updated = limit spent → 402).
 */
class PdfQuotaServiceTest {

    private static final String RETAILER = "retailer-1";
    private static final String CUSTOMER = "customer-1";
    private static final String ORG = "org-1";
    private static final String SUB_ID = "sub-1";
    private static final String CODE_ID = "code-1";

    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CustomerAccessCodeRepository codes = mock(CustomerAccessCodeRepository.class);
    private final OrgMembershipRepository memberships = mock(OrgMembershipRepository.class);

    private final PdfQuotaService service =
            new PdfQuotaService(subs, users, codes, memberships,
                    mock(com.gridstore.huevista.billing.service.UnbilledAccounts.class));

    /**
     * The guest board floor, injected by hand because this test builds the service with
     * `new` rather than through Spring, so its {@code @Value} field would otherwise sit
     * at 0 and make the floor a no-op — quietly passing every assertion below.
     */
    {
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "guestImagesPerBoard", 5);
    }

    private static User user(String id, UserRole role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    private static Subscription sub(int pdfUsed, int pdfLimit) {
        return Subscription.builder()
                .id(SUB_ID).user(user(RETAILER, UserRole.RETAILER)).plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .projectsLimit(60)
                .pdfDownloadsUsed(pdfUsed).pdfDownloadsLimit(pdfLimit)
                .pdfImageLimit(8)
                .build();
    }

    // ---- retailer: own plan ----

    @Test
    void retailer_allowance_comes_from_own_subscription() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(sub(3, 100)));

        PdfAllowanceResponse a = service.allowanceForUser(RETAILER);

        assertThat(a.getImagesPerPdf()).isEqualTo(8);
        assertThat(a.getUsed()).isEqualTo(3);
        assertThat(a.getRemaining()).isEqualTo(97);
        assertThat(a.isUnlimited()).isFalse();
    }

    @Test
    void retailer_without_subscription_gets_402() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.allowanceForUser(RETAILER))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void reserve_charges_one_download_atomically() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(sub(3, 100)));
        when(subs.incrementPdfUsageIfWithinLimit(SUB_ID)).thenReturn(1);
        when(subs.findById(SUB_ID)).thenReturn(Optional.of(sub(4, 100)));

        PdfAllowanceResponse a = service.reserveForUser(RETAILER);

        verify(subs).incrementPdfUsageIfWithinLimit(SUB_ID);
        assertThat(a.getUsed()).isEqualTo(4);
        assertThat(a.getRemaining()).isEqualTo(96);
    }

    @Test
    void reserve_at_limit_throws_402_and_never_overcounts() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(sub(100, 100)));
        when(subs.incrementPdfUsageIfWithinLimit(SUB_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.reserveForUser(RETAILER))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("PDF download limit");
    }

    // ---- customer: no monthly counter; the cap is per project ----

    /**
     * A customer's boards are limited by the project, not by anybody's subscription.
     *
     * <p>This used to walk customer → entitlement → retailerOrg → that org's owner → an
     * ACTIVE subscription, and every missing link produced the same 402: "PDF downloads
     * are covered by your paint shop's plan — redeem a shop access code first", shown to
     * people who had redeemed one. It was wrong twice over. The board was already paid
     * for — a shop's code costs the shop a project when the code is GENERATED, and a
     * project bought direct was paid for at the till — so charging it again to a monthly
     * plan billed the same thing twice; and it let a shop's lapsed subscription silently
     * take the boards away from every customer it had ever onboarded.
     */
    @Test
    void customer_has_no_monthly_counter_because_the_cap_is_per_project() {
        when(users.findById(CUSTOMER)).thenReturn(Optional.of(user(CUSTOMER, UserRole.CUSTOMER)));

        PdfAllowanceResponse a = service.allowanceForUser(CUSTOMER);

        assertThat(a.isUnlimited()).isTrue();
        // No shop was consulted in either direction — that is the whole point.
        verify(memberships, never()).findUserIdsByOrganizationIdAndRole(any(), any());
    }

    @Test
    void customer_with_no_shop_at_all_still_gets_their_board() {
        // The case with no action behind the old advice: somebody who bought their own
        // project has no retailerOrg and never will, and was told to go and find a code.
        when(users.findById(CUSTOMER)).thenReturn(Optional.of(user(CUSTOMER, UserRole.CUSTOMER)));

        assertThat(service.reserveForUser(CUSTOMER).isUnlimited()).isTrue();
        verify(subs, never()).incrementPdfUsageIfWithinLimit(any());
    }

    // ---- guest: the issuing shop's plan via the access code ----

    @Test
    void guest_reserve_bills_the_issuing_shop() {
        Organization org = new Organization();
        org.setId(ORG);
        CustomerAccessCode code = CustomerAccessCode.builder().organization(org).code("ABCD2345").build();
        when(codes.findById(CODE_ID)).thenReturn(Optional.of(code));
        when(memberships.findUserIdsByOrganizationIdAndRole(ORG, OrgMemberRole.OWNER))
                .thenReturn(List.of(RETAILER));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(sub(0, 100)));
        when(subs.incrementPdfUsageIfWithinLimit(SUB_ID)).thenReturn(1);
        when(subs.findById(SUB_ID)).thenReturn(Optional.of(sub(1, 100)));

        PdfAllowanceResponse a = service.reserveForGuest(CODE_ID);

        verify(subs).incrementPdfUsageIfWithinLimit(SUB_ID);
        assertThat(a.getUsed()).isEqualTo(1);
    }

    /**
     * A walk-in's board carries five pictures even though the shop paying for it is on a
     * plan whose own documents hold four. The board is now the whole deliverable — one
     * download and the project is finished — so it is sized for the person carrying it
     * out of the shop rather than for the plan behind the counter.
     */
    @Test
    void guest_board_carries_five_pictures_on_a_small_plan() {
        Organization org = new Organization();
        org.setId(ORG);
        CustomerAccessCode code = CustomerAccessCode.builder().organization(org).code("ABCD2345").build();
        when(codes.findById(CODE_ID)).thenReturn(Optional.of(code));
        when(memberships.findUserIdsByOrganizationIdAndRole(ORG, OrgMemberRole.OWNER))
                .thenReturn(List.of(RETAILER));
        Subscription small = sub(0, 100);
        small.setPdfImageLimit(4);
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(small));

        assertThat(service.allowanceForGuest(CODE_ID).getImagesPerPdf()).isEqualTo(5);
    }

    /** A floor, not a ceiling: a bigger shop's customer keeps the bigger board. */
    @Test
    void guest_board_never_shrinks_a_bigger_plan() {
        Organization org = new Organization();
        org.setId(ORG);
        CustomerAccessCode code = CustomerAccessCode.builder().organization(org).code("ABCD2345").build();
        when(codes.findById(CODE_ID)).thenReturn(Optional.of(code));
        when(memberships.findUserIdsByOrganizationIdAndRole(ORG, OrgMemberRole.OWNER))
                .thenReturn(List.of(RETAILER));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(sub(0, 100))); // PROFESSIONAL: 8 per document

        assertThat(service.allowanceForGuest(CODE_ID).getImagesPerPdf()).isEqualTo(8);
    }

    /** An account holder is untouched — their cap is their own plan's, floor or no floor. */
    @Test
    void an_account_holders_board_is_not_widened() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        Subscription small = sub(0, 100);
        small.setPdfImageLimit(4);
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(small));

        assertThat(service.allowanceForUser(RETAILER).getImagesPerPdf()).isEqualTo(4);
    }

    @Test
    void guest_of_shop_without_plan_gets_402() {
        Organization org = new Organization();
        org.setId(ORG);
        CustomerAccessCode code = CustomerAccessCode.builder().organization(org).code("ABCD2345").build();
        when(codes.findById(CODE_ID)).thenReturn(Optional.of(code));
        when(memberships.findUserIdsByOrganizationIdAndRole(ORG, OrgMemberRole.OWNER))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.allowanceForGuest(CODE_ID))
                .isInstanceOf(QuotaExceededException.class);
    }

    // ---- unlimited plans ----

    @Test
    void unlimited_plan_reports_unlimited() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findEntitling(eq(RETAILER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(sub(5, Integer.MAX_VALUE)));

        PdfAllowanceResponse a = service.allowanceForUser(RETAILER);

        assertThat(a.isUnlimited()).isTrue();
        assertThat(a.getRemaining()).isEqualTo(Integer.MAX_VALUE);
    }
}
