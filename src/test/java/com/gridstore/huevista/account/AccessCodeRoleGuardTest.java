package com.gridstore.huevista.account;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.service.AccessCodeService;
import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.JwtService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Redeeming an access code flips the account to CUSTOMER — which must never be able
 * to demote a RETAILER / ADMIN / DISTRIBUTOR / PAINTER account that typed a code in
 * by mistake. Only CUSTOMER (and legacy null-role) accounts may redeem.
 */
class AccessCodeRoleGuardTest {

    private final CustomerAccessCodeRepository codes = mock(CustomerAccessCodeRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final OrgMembershipRepository memberships = mock(OrgMembershipRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CustomerEntitlementService entitlements = mock(CustomerEntitlementService.class);
    private final JwtService jwt = mock(JwtService.class);

    private final AccessCodeService service =
            new AccessCodeService(codes, orgs, memberships, users, entitlements, jwt);

    private static CustomerAccessCode validCode() {
        Organization org = new Organization();
        org.setId("org-1");
        return CustomerAccessCode.builder()
                .id("code-1")
                .organization(org)
                .code("ABCD2345")
                .validDays(7)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId("user-1");
        u.setRole(role);
        return u;
    }

    @Test
    void retailer_redeeming_is_rejected_and_keeps_their_role() {
        when(codes.findByCode("ABCD2345")).thenReturn(Optional.of(validCode()));
        when(users.findById("user-1")).thenReturn(Optional.of(user(UserRole.RETAILER)));

        assertThatThrownBy(() -> service.redeemCode("user-1", "abcd2345"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retailer");

        verifyNoInteractions(entitlements);
    }

    @Test
    void admin_redeeming_is_rejected() {
        when(codes.findByCode("ABCD2345")).thenReturn(Optional.of(validCode()));
        when(users.findById("user-1")).thenReturn(Optional.of(user(UserRole.ADMIN)));

        assertThatThrownBy(() -> service.redeemCode("user-1", "ABCD2345"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customer_redeeming_succeeds() {
        CustomerAccessCode code = validCode();
        when(codes.findByCode("ABCD2345")).thenReturn(Optional.of(code));
        when(users.findById("user-1")).thenReturn(Optional.of(user(UserRole.CUSTOMER)));
        when(codes.consumeForUser(eq("code-1"), any(User.class), any(LocalDateTime.class))).thenReturn(1);

        service.redeemCode("user-1", "ABCD2345");

        verify(entitlements).onAccessCodeRedeemed(any(User.class), eq(code.getOrganization()), eq(7));
        assertThat(code.getUsedAt()).isNotNull();
    }
}
