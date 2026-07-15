package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Colour-board PDF quota: a retailer spends their own plan, a CUSTOMER account and a
 * guest both spend the issuing shop's plan, and reservation is the atomic conditional
 * UPDATE (0 rows updated = limit spent → 402).
 */
class PdfQuotaServiceTest {

    private static final String RETAILER = "retailer-1";
    private static final String CUSTOMER = "customer-1";
    private static final String ORG = "org-1";
    private static final String SUB_ID = "sub-1";
    private static final String CODE_ID = "code-1";

    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CustomerEntitlementRepository entitlements = mock(CustomerEntitlementRepository.class);
    private final CustomerAccessCodeRepository codes = mock(CustomerAccessCodeRepository.class);
    private final OrgMembershipRepository memberships = mock(OrgMembershipRepository.class);

    private final PdfQuotaService service =
            new PdfQuotaService(subs, users, entitlements, codes, memberships);

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
                .aiGenerationsLimit(60)
                .pdfDownloadsUsed(pdfUsed).pdfDownloadsLimit(pdfLimit)
                .pdfImageLimit(8)
                .build();
    }

    // ---- retailer: own plan ----

    @Test
    void retailer_allowance_comes_from_own_subscription() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub(3, 100)));

        PdfAllowanceResponse a = service.allowanceForUser(RETAILER);

        assertThat(a.getImagesPerPdf()).isEqualTo(8);
        assertThat(a.getUsed()).isEqualTo(3);
        assertThat(a.getRemaining()).isEqualTo(97);
        assertThat(a.isUnlimited()).isFalse();
    }

    @Test
    void retailer_without_subscription_gets_402() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.allowanceForUser(RETAILER))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void reserve_charges_one_download_atomically() {
        when(users.findById(RETAILER)).thenReturn(Optional.of(user(RETAILER, UserRole.RETAILER)));
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub(3, 100)));
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
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub(100, 100)));
        when(subs.incrementPdfUsageIfWithinLimit(SUB_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.reserveForUser(RETAILER))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("PDF download limit");
    }

    // ---- customer: the issuing shop's plan ----

    @Test
    void customer_rides_on_the_issuing_shops_plan() {
        when(users.findById(CUSTOMER)).thenReturn(Optional.of(user(CUSTOMER, UserRole.CUSTOMER)));
        Organization org = new Organization();
        org.setId(ORG);
        CustomerEntitlement ent = CustomerEntitlement.builder()
                .customer(user(CUSTOMER, UserRole.CUSTOMER))
                .retailerOrg(org)
                .accessExpiresAt(java.time.LocalDateTime.now().plusDays(5))
                .build();
        when(entitlements.findByCustomerId(CUSTOMER)).thenReturn(Optional.of(ent));
        when(memberships.findUserIdsByOrganizationIdAndRole(ORG, OrgMemberRole.OWNER))
                .thenReturn(List.of(RETAILER));
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub(10, 100)));

        PdfAllowanceResponse a = service.allowanceForUser(CUSTOMER);

        assertThat(a.getRemaining()).isEqualTo(90);
    }

    @Test
    void customer_without_entitlement_gets_402() {
        when(users.findById(CUSTOMER)).thenReturn(Optional.of(user(CUSTOMER, UserRole.CUSTOMER)));
        when(entitlements.findByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.allowanceForUser(CUSTOMER))
                .isInstanceOf(QuotaExceededException.class);
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
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub(0, 100)));
        when(subs.incrementPdfUsageIfWithinLimit(SUB_ID)).thenReturn(1);
        when(subs.findById(SUB_ID)).thenReturn(Optional.of(sub(1, 100)));

        PdfAllowanceResponse a = service.reserveForGuest(CODE_ID);

        verify(subs).incrementPdfUsageIfWithinLimit(SUB_ID);
        assertThat(a.getUsed()).isEqualTo(1);
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
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(RETAILER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub(5, Integer.MAX_VALUE)));

        PdfAllowanceResponse a = service.allowanceForUser(RETAILER);

        assertThat(a.isUnlimited()).isTrue();
        assertThat(a.getRemaining()).isEqualTo(Integer.MAX_VALUE);
    }
}
