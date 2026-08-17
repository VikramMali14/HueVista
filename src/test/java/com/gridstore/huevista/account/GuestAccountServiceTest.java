package com.gridstore.huevista.account;

import com.gridstore.huevista.account.dto.GuestMergeResponse;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.service.AccessCodeService;
import com.gridstore.huevista.account.service.GuestAccountService;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.util.Emails;
import com.gridstore.huevista.billing.model.AiCreditWallet;
import com.gridstore.huevista.billing.repository.AiCreditWalletRepository;
import com.gridstore.huevista.billing.repository.ProjectCreditRepository;
import com.gridstore.huevista.billing.repository.RewardPointsLotRepository;
import com.gridstore.huevista.billing.repository.RewardPointsTransactionRepository;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.maskreport.repository.MaskReportRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The walk-in's account, and what happens when they claim it.
 *
 * <p>Two properties matter most here and are asserted directly: a purchase made with an
 * address that already has an account must land on THAT account (otherwise the customer
 * accumulates a new account per visit), and a merge must be refused unless the account
 * being merged away is genuinely an unclaimed one (otherwise a session token becomes a
 * way to strip a stranger's real account).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuestAccountServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private CustomerAccessCodeRepository codeRepository;
    @Mock private CustomerEntitlementRepository entitlementRepository;
    @Mock private AccessCodeService accessCodeService;
    @Mock private ProjectRepository projectRepository;
    @Mock private ImageRepository imageRepository;
    @Mock private MaskReportRepository maskReportRepository;
    @Mock private ProjectCreditRepository projectCreditRepository;
    @Mock private AiCreditWalletRepository walletRepository;
    @Mock private RewardPointsLotRepository pointsLotRepository;
    @Mock private RewardPointsTransactionRepository pointsTxnRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private com.gridstore.huevista.auth.service.JwtService jwtService;
    @Mock private AuditService auditService;

    @InjectMocks private GuestAccountService service;

    private final Organization org = Organization.builder().id("org-1").name("Mehta Paints").build();

    private CustomerAccessCode kioskCode() {
        return CustomerAccessCode.builder()
                .id("code-1").organization(org).code("7KQ2XR9M").selfFunded(true).projectQuota(1).build();
    }

    private User saved(String id) {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(id);
            return u;
        });
        return null;
    }

    @Nested
    class Provisioning {

        @Test
        void opensAnAccountKeyedToTheAddressTheBuyerGave() {
            CustomerAccessCode code = kioskCode();
            when(userRepository.findByEmailAndDeletedAtIsNull("priya@example.com"))
                    .thenReturn(Optional.empty());
            saved("guest-1");

            User guest = service.provisionForKiosk(code, "  Priya@Example.com ", null);

            assertThat(guest.getEmail()).isEqualTo("priya@example.com");
            assertThat(guest.getProvider()).isEqualTo(AuthProvider.ACCESS_CODE);
            assertThat(guest.getRole()).isEqualTo(UserRole.CUSTOMER);
            assertThat(guest.getPassword()).isNull();
            // Not verified: they typed it at a till and nobody has proved they can read it.
            assertThat(guest.isEmailVerified()).isFalse();
            // The address is on the code too — it is where the receipt goes and how they
            // get back in, even if the account ends up keyed to something else.
            assertThat(code.getBuyerEmail()).isEqualTo("priya@example.com");
            verify(accessCodeService).redeemCode("guest-1", "7KQ2XR9M");
        }

        @Test
        void attachesToAnAccountTheBuyerAlreadyHasInsteadOfOpeningASecond() {
            CustomerAccessCode code = kioskCode();
            User existing = User.builder().id("real-1").email("priya@example.com")
                    .role(UserRole.CUSTOMER).provider(AuthProvider.LOCAL).build();
            when(userRepository.findByEmailAndDeletedAtIsNull("priya@example.com"))
                    .thenReturn(Optional.of(existing));

            User owner = service.provisionForKiosk(code, "priya@example.com", null);

            assertThat(owner).isSameAs(existing);
            verify(accessCodeService).redeemCode("real-1", "7KQ2XR9M");
            // Nothing new opened — the repeat customer keeps one account.
            verify(userRepository, never()).save(any(User.class));
        }

        /**
         * A shop owner buying at their own kiosk. Redeeming onto their account would flip
         * it to CUSTOMER and take their org ownership with it, so the purchase gets its
         * own account and they can merge it wherever they like afterwards.
         */
        @Test
        void doesNotRedeemOntoANonCustomerAccountThatOwnsTheAddress() {
            CustomerAccessCode code = kioskCode();
            User shop = User.builder().id("shop-1").email("mehta@example.com")
                    .role(UserRole.RETAILER).provider(AuthProvider.LOCAL).build();
            when(userRepository.findByEmailAndDeletedAtIsNull("mehta@example.com"))
                    .thenReturn(Optional.of(shop));
            saved("guest-2");

            User guest = service.provisionForKiosk(code, "mehta@example.com", null);

            assertThat(guest.getId()).isEqualTo("guest-2");
            assertThat(guest.getEmail()).isEqualTo(Emails.syntheticFor("7KQ2XR9M"));
            verify(accessCodeService).redeemCode("guest-2", "7KQ2XR9M");
            verify(accessCodeService, never()).redeemCode(eq("shop-1"), anyString());
        }

        @Test
        void aBuyerWhoGivesNoAddressStillGetsWhatTheyPaidFor() {
            CustomerAccessCode code = kioskCode();
            saved("guest-3");

            User guest = service.provisionForKiosk(code, null, null);

            assertThat(guest.getEmail()).isEqualTo(Emails.syntheticFor("7KQ2XR9M"));
            assertThat(Emails.isSynthetic(guest)).isTrue();
            assertThat(code.getBuyerEmail()).isNull();
            verify(accessCodeService).redeemCode("guest-3", "7KQ2XR9M");
        }

        /**
         * This request arrives AFTER the money moved. A malformed address must cost the
         * customer their receipt, never their purchase.
         */
        @Test
        void aMistypedAddressDoesNotFailThePaidForPurchase() {
            CustomerAccessCode code = kioskCode();
            saved("guest-4");

            User guest = service.provisionForKiosk(code, "priya-at-example", null);

            assertThat(guest.getEmail()).isEqualTo(Emails.syntheticFor("7KQ2XR9M"));
            assertThat(code.getBuyerEmail()).isNull();
            verify(accessCodeService).redeemCode("guest-4", "7KQ2XR9M");
        }

        /** Nobody may claim an address in the domain the platform generates for itself. */
        @Test
        void refusesAnAddressInThePlatformsOwnPlaceholderDomain() {
            CustomerAccessCode code = kioskCode();
            saved("guest-5");

            User guest = service.provisionForKiosk(code, "ac-someoneelse" + Emails.SYNTHETIC_DOMAIN, null);

            assertThat(guest.getEmail()).isEqualTo(Emails.syntheticFor("7KQ2XR9M"));
            assertThat(code.getBuyerEmail()).isNull();
        }
    }

    @Nested
    class Merging {

        private User guest() {
            return User.builder().id("guest-1").email("priya@example.com").name("priya")
                    .provider(AuthProvider.ACCESS_CODE).role(UserRole.CUSTOMER).password(null).build();
        }

        private User target() {
            return User.builder().id("real-1").email("priya@home.example").name("Priya")
                    .provider(AuthProvider.LOCAL).role(UserRole.CUSTOMER).password("$2a$hash").build();
        }

        @Test
        void movesEverythingAndRetiresTheKioskAccount() {
            User guest = guest();
            User target = target();
            when(userRepository.findById("guest-1")).thenReturn(Optional.of(guest));
            when(userRepository.findById("real-1")).thenReturn(Optional.of(target));
            when(codeRepository.findFirstByUsedByUserIdOrderByCreatedAtDesc("guest-1"))
                    .thenReturn(Optional.of(kioskCode()));
            when(projectRepository.reassignOwner(any(), eq("guest-1"))).thenReturn(2);
            when(imageRepository.reassignOwner(any(), eq("guest-1"))).thenReturn(2);
            when(entitlementRepository.findByCustomerId("guest-1")).thenReturn(Optional.of(
                    CustomerEntitlement.builder().customer(guest).retailerOrg(org)
                            .projectAllowance(1).projectsCreated(1).build()));
            when(entitlementRepository.findByCustomerId("real-1")).thenReturn(Optional.of(
                    CustomerEntitlement.builder().customer(target).retailerOrg(org)
                            .projectAllowance(3).projectsCreated(2).build()));
            when(walletRepository.findByUserId("guest-1")).thenReturn(Optional.of(
                    AiCreditWallet.builder().userId("guest-1").balance(4).build()));
            when(walletRepository.findByUserId("real-1")).thenReturn(Optional.empty());

            GuestMergeResponse res = service.merge("real-1", "guest-1");

            assertThat(res.getProjectsMoved()).isEqualTo(2);
            assertThat(res.getImagesMoved()).isEqualTo(2);
            assertThat(res.getProjectAllowanceMoved()).isEqualTo(1);
            assertThat(res.getAiCreditsMoved()).isEqualTo(4);
            assertThat(res.getShopName()).isEqualTo("Mehta Paints");

            // The shop's own record of who holds its code follows the work.
            verify(codeRepository).reassignRedeemer(any(), eq("guest-1"));
            verify(projectCreditRepository).reassignOwner("guest-1", "real-1");
            verify(pointsLotRepository).reassignOwner("guest-1", "real-1");

            // Allowance and rooms-created move together, or the customer is either given
            // free slots or billed twice for the rooms that came across.
            ArgumentCaptor<CustomerEntitlement> ent = ArgumentCaptor.forClass(CustomerEntitlement.class);
            verify(entitlementRepository).save(ent.capture());
            assertThat(ent.getValue().getProjectAllowance()).isEqualTo(4);
            assertThat(ent.getValue().getProjectsCreated()).isEqualTo(3);

            // Retired: sessions gone, address freed, forwarding note left.
            verify(refreshTokenRepository).deleteByUser(guest);
            assertThat(guest.getDeletedAt()).isNotNull();
            assertThat(guest.getMergedIntoUserId()).isEqualTo("real-1");
            assertThat(guest.getEmail()).doesNotContain("priya@example.com");
        }

        /**
         * The token proves possession of a session, not that the account behind it was
         * ever unclaimed. Without this, handing over a token for somebody's real account
         * would strip it of everything it owns.
         */
        @Test
        void refusesToMergeAwayAFullAccount() {
            User realOne = User.builder().id("guest-1").email("someone@example.com")
                    .provider(AuthProvider.LOCAL).role(UserRole.CUSTOMER).password("$2a$hash").build();
            when(userRepository.findById("guest-1")).thenReturn(Optional.of(realOne));
            when(userRepository.findById("real-1")).thenReturn(Optional.of(target()));

            assertThatThrownBy(() -> service.merge("real-1", "guest-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("full account");

            verify(projectRepository, never()).reassignOwner(any(), anyString());
        }

        /** A shop account absorbing customer entitlements would wreck its own role. */
        @Test
        void refusesToMergeIntoANonCustomerAccount() {
            when(userRepository.findById("guest-1")).thenReturn(Optional.of(guest()));
            when(userRepository.findById("shop-1")).thenReturn(Optional.of(
                    User.builder().id("shop-1").email("shop@example.com")
                            .provider(AuthProvider.LOCAL).role(UserRole.RETAILER).build()));

            assertThatThrownBy(() -> service.merge("shop-1", "guest-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("customer account");

            verify(projectRepository, never()).reassignOwner(any(), anyString());
        }

        @Test
        void refusesToMergeAnAccountIntoItself() {
            when(userRepository.findById("guest-1")).thenReturn(Optional.of(guest()));

            assertThatThrownBy(() -> service.merge("guest-1", "guest-1"))
                    .isInstanceOf(IllegalStateException.class);

            verify(projectRepository, never()).reassignOwner(any(), anyString());
        }

        @Test
        void refusesAnInvalidOrExpiredKioskSessionToken() {
            when(jwtService.isTokenValid("nonsense")).thenReturn(false);

            assertThatThrownBy(() -> service.mergeUsingGuestToken("real-1", "nonsense"))
                    .isInstanceOf(SecurityException.class);

            verify(projectRepository, never()).reassignOwner(any(), anyString());
        }
    }
}
