package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.dto.GuestMergeResponse;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
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
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.maskreport.repository.MaskReportRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The walk-in's account: opened for them at the kiosk till, claimable afterwards.
 *
 * <p><b>A guest account is a real account.</b> It is a passwordless {@code CUSTOMER} row
 * with {@link AuthProvider#ACCESS_CODE}, and nothing downstream treats it specially —
 * entitlements, project ownership, quotas and the studio all work because it is an
 * ordinary account, not a second kind of owner. That is deliberate: the previous
 * anonymous route made every project either user-owned OR code-owned, which meant two
 * ownership models in every query, and the schema has since closed it off for good
 * ({@code projects.user_id} is NOT NULL). There is one owner model here, and a guest
 * account is simply one nobody has claimed yet.
 *
 * <p><b>The receipt is not a password.</b> A code that no longer expires would, if it
 * alone reopened the account, be a permanent credential printed on a slip of till paper
 * — findable in a bin, readable over a shoulder, and good forever. So the address the
 * buyer gives at checkout is what reopens the account (see
 * {@code KioskReentryService}), and the eight characters stay what the shop reads at
 * the counter to mix the paint.
 *
 * <p><b>Merging is one-way and total.</b> Everything moves and the guest account is
 * retired. A merge that left the guest account alive would leave the customer with two
 * accounts holding halves of one purchase, and no way to tell which is which — the
 * outcome the customer wants from "add this to my account" is that afterwards there is
 * only their account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestAccountService {

    private final UserRepository userRepository;
    private final CustomerAccessCodeRepository codeRepository;
    private final CustomerEntitlementRepository entitlementRepository;
    private final AccessCodeService accessCodeService;
    private final ProjectRepository projectRepository;
    private final ImageRepository imageRepository;
    private final MaskReportRepository maskReportRepository;
    private final ProjectCreditRepository projectCreditRepository;
    private final AiCreditWalletRepository walletRepository;
    private final RewardPointsLotRepository pointsLotRepository;
    private final RewardPointsTransactionRepository pointsTxnRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final com.gridstore.huevista.auth.service.JwtService jwtService;
    private final AuditService auditService;

    /**
     * Open (or reuse) the account a kiosk purchase belongs to, and redeem the paid-for
     * code onto it. Returns the account the buyer should be signed into.
     *
     * <p>When the address they typed already has a live customer account, the purchase
     * lands straight on it and no guest account is created at all — the best possible
     * outcome, because there is then nothing to merge later. That case is common: the
     * repeat customer buying a second room.
     *
     * @param code     the freshly issued, unredeemed kiosk code
     * @param rawEmail what the buyer typed at the till; may be null when they declined
     * @param rawName  what the buyer typed as their name; may be null
     */
    @Transactional
    public User provisionForKiosk(CustomerAccessCode code, String rawEmail, String rawName) {
        String email = usableEmail(rawEmail);

        // Recorded on the code even when the account ends up keyed to something else:
        // this is where the receipt goes and how the buyer gets back in.
        if (email != null) {
            code.setBuyerEmail(email);
            codeRepository.save(code);
        }

        if (email != null) {
            User existing = userRepository.findByEmailAndDeletedAtIsNull(email).orElse(null);
            if (existing != null && existing.getRole() == UserRole.CUSTOMER) {
                accessCodeService.redeemCode(existing.getId(), code.getCode());
                log.info("Kiosk purchase attached to existing customer account {} (code {})",
                        existing.getId(), code.getCode());
                return existing;
            }
            if (existing != null) {
                // A shop owner, painter or admin buying at a kiosk. Redeeming onto their
                // account would destroy the role their whole login depends on, and the
                // address is taken, so the guest account gets a synthetic one. They can
                // still merge it into whatever account they choose afterwards.
                log.info("Kiosk buyer's address belongs to a {} account — opening a separate "
                        + "guest account for code {}", existing.getRole(), code.getCode());
                email = null;
            }
        }

        User guest = userRepository.save(User.builder()
                .email(email != null ? email : Emails.syntheticFor(code.getCode()))
                .password(null)
                .name(displayName(rawName, email))
                .provider(AuthProvider.ACCESS_CODE)
                .role(UserRole.CUSTOMER)
                // Nothing has proved the address yet — the buyer typed it at a till. It is
                // good enough to send a receipt and a sign-in code to, and not good enough
                // to count as verified.
                .emailVerified(false)
                .build());

        accessCodeService.redeemCode(guest.getId(), code.getCode());
        log.info("Kiosk guest account opened: user={} code={} contactable={}",
                guest.getId(), code.getCode(), email != null);
        return guest;
    }

    /**
     * The buyer's address if it is one, null otherwise.
     *
     * <p>Nothing here refuses the purchase. This runs after the customer has paid, so a
     * mistyped address must cost them their receipt, not their money — the kiosk checks
     * the address before opening Checkout, where saying no is free. A value that reaches
     * this point malformed is treated as though they had declined to give one.
     */
    private String usableEmail(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        if (email == null || email.isBlank() || email.length() > 320) return null;
        int at = email.indexOf('@');
        boolean shaped = at > 0
                && at == email.lastIndexOf('@')
                && email.indexOf('.', at) > at + 1
                && !email.endsWith(".")
                && !email.contains(" ");
        if (!shaped) {
            log.info("Kiosk buyer's address was not usable — the purchase is unattached to an inbox");
            return null;
        }
        // A caller must not be able to claim an address the platform generates for itself.
        return email.endsWith(Emails.SYNTHETIC_DOMAIN) ? null : email;
    }

    /**
     * Fold the kiosk account a session token belongs to into the signed-in customer's
     * account.
     *
     * <p>The token is the proof of ownership, and it is the right one: it is the session
     * handed to whoever was standing at the till when the payment cleared, held in the
     * browser that made the purchase. Asking for the printed code instead would let
     * anyone who read a discarded receipt pull a stranger's room into their own account.
     */
    @Transactional
    public GuestMergeResponse mergeUsingGuestToken(String targetUserId, String guestToken) {
        if (guestToken == null || guestToken.isBlank() || !jwtService.isTokenValid(guestToken)) {
            throw new SecurityException(
                    "That kiosk session has expired. Ask for a sign-in code by email, then try again.");
        }
        return merge(targetUserId, jwtService.extractUserId(guestToken));
    }

    /**
     * Fold a kiosk guest account into the signed-in customer's real account: move
     * everything it owns, then retire it.
     *
     * @param targetUserId the real account, which must be the signed-in caller
     * @param guestUserId  the kiosk account, proven by the caller holding its session
     */
    @Transactional
    public GuestMergeResponse merge(String targetUserId, String guestUserId) {
        User target = liveUser(targetUserId, "Your account could not be found.");
        User guest = liveUser(guestUserId, "That kiosk session has already been used or closed.");

        if (target.getId().equals(guest.getId())) {
            throw new IllegalStateException(
                    "You are already signed in to the kiosk account — there is nothing to move.");
        }
        // Only a customer account can hold redeemed codes and project entitlements. The
        // same rule AccessCodeService#redeemCode enforces, for the same reason: absorbing
        // them into a shop account would wreck the role its org ownership hangs on.
        if (target.getRole() != UserRole.CUSTOMER) {
            throw new IllegalStateException(
                    "This is a " + String.valueOf(target.getRole()).toLowerCase()
                    + " account. A kiosk room can only be moved into a customer account — "
                    + "sign in with the customer account you want to keep it on.");
        }
        // The token proves possession of a session, not that the account behind it was
        // ever a guest. Without this check anyone could hand over a token for a real
        // account and strip it of everything it owns.
        if (!isGuestAccount(guest)) {
            throw new IllegalStateException(
                    "That account is a full account with its own sign-in, so it can't be "
                    + "merged away. Sign in to it directly to reach its rooms.");
        }

        String shopName = codeRepository.findFirstByUsedByUserIdOrderByCreatedAtDesc(guest.getId())
                .map(c -> c.getOrganization().getName())
                .orElse(null);

        // Bulk moves first. Each clears the persistence context, so nothing loaded above
        // may be touched after this block without being read again.
        int projects = projectRepository.reassignOwner(target, guest.getId());
        int images = imageRepository.reassignOwner(target, guest.getId());
        maskReportRepository.reassignReporter(target, guest.getId());
        codeRepository.reassignRedeemer(target, guest.getId());
        projectCreditRepository.reassignOwner(guest.getId(), target.getId());
        pointsLotRepository.reassignOwner(guest.getId(), target.getId());
        pointsTxnRepository.reassignOwner(guest.getId(), target.getId());

        target = liveUser(targetUserId, "Your account could not be found.");
        guest = liveUser(guestUserId, "That kiosk session has already been used or closed.");

        int allowance = mergeEntitlement(guest.getId(), target);
        int aiCredits = mergeWallet(guest.getId(), target);

        retire(guest, target);

        auditService.record(target.getId(), "GUEST_ACCOUNT_MERGED", "USER", guest.getId(),
                "projects=" + projects + " images=" + images
                        + " allowance=" + allowance + " aiCredits=" + aiCredits);
        log.info("Guest account {} merged into {}: projects={} images={} allowance={} aiCredits={}",
                guest.getId(), target.getId(), projects, images, allowance, aiCredits);

        return GuestMergeResponse.builder()
                .mergedFromUserId(guest.getId())
                .projectsMoved(projects)
                .imagesMoved(images)
                .projectAllowanceMoved(allowance)
                .aiCreditsMoved(aiCredits)
                .shopName(shopName)
                .build();
    }

    /**
     * An account nobody has claimed: opened by redeeming a code and never given a
     * password, so its only ways in are the session it was created with and an e-mailed
     * sign-in code. An account that has since set a password is its owner's and is not
     * merge-able away from them.
     */
    public boolean isGuestAccount(User user) {
        return user != null
                && user.getDeletedAt() == null
                && user.getProvider() == AuthProvider.ACCESS_CODE
                && (user.getPassword() == null || user.getPassword().isBlank());
    }

    /** Add the guest's project allowance to the target's, or hand the whole row over. */
    private int mergeEntitlement(String guestUserId, User target) {
        CustomerEntitlement from = entitlementRepository.findByCustomerId(guestUserId).orElse(null);
        if (from == null) return 0;

        int allowance = from.getProjectAllowance();
        CustomerEntitlement to = entitlementRepository.findByCustomerId(target.getId()).orElse(null);
        if (to == null) {
            from.setCustomer(target);
            entitlementRepository.save(from);
        } else {
            // projectsCreated travels with the allowance because the rooms it counted are
            // travelling too. Adding one without the other would either hand the customer
            // free slots or bill them for rooms twice.
            to.setProjectAllowance(to.getProjectAllowance() + allowance);
            to.setProjectsCreated(to.getProjectsCreated() + from.getProjectsCreated());
            entitlementRepository.save(to);
            entitlementRepository.delete(from);
        }
        return allowance;
    }

    /** Carry unspent AI credits across; one wallet per account, so they fold. */
    private int mergeWallet(String guestUserId, User target) {
        AiCreditWallet from = walletRepository.findByUserId(guestUserId).orElse(null);
        if (from == null) return 0;

        int balance = from.getBalance();
        AiCreditWallet to = walletRepository.findByUserId(target.getId()).orElse(null);
        if (to == null) {
            from.setUserId(target.getId());
            walletRepository.save(from);
        } else {
            to.setBalance(to.getBalance() + balance);
            walletRepository.save(to);
            walletRepository.delete(from);
        }
        return balance;
    }

    /**
     * Close the emptied account: sessions revoked, PII scrubbed, address freed so the
     * customer can register with it properly later, and a pointer left to where its
     * contents went.
     *
     * <p>Tombstoned rather than deleted, because the shop's issued code still refers to
     * this row in its own history and support has to be able to answer "where did that
     * walk-in's room go".
     */
    private void retire(User guest, User target) {
        refreshTokenRepository.deleteByUser(guest);
        guest.setEmail("merged-" + guest.getId() + "@merged.huevista.invalid");
        guest.setName("Merged kiosk account");
        guest.setPassword(null);
        guest.setPicture(null);
        guest.setProviderId(null);
        guest.setPhoneNumber(null);
        guest.setPhoneVerified(false);
        guest.setEmailVerified(false);
        guest.setMergedIntoUserId(target.getId());
        guest.setDeletedAt(LocalDateTime.now());
        userRepository.save(guest);
    }

    private User liveUser(String userId, String message) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(message));
    }

    /**
     * Something to greet the customer by. The address's local part beats "Guest" when
     * they gave one — the studio header reads "Hi priya", not "Hi Guest" — but anything
     * that looks like a machine identifier is not worth showing.
     */
    private String displayName(String rawName, String email) {
        if (rawName != null && !rawName.isBlank()) {
            return rawName.trim();
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).trim();
            if (local.length() >= 2 && local.length() <= 40) return local;
        }
        return "Guest";
    }
}
