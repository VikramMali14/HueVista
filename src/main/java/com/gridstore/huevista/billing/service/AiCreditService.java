package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.AiCreditTransaction;
import com.gridstore.huevista.billing.model.AiCreditWallet;
import com.gridstore.huevista.billing.repository.AiCreditTransactionRepository;
import com.gridstore.huevista.billing.repository.AiCreditWalletRepository;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * The AI credit ledger: the balance that buys photorealistic renders.
 *
 * One credit is one AI image. That is the whole exchange rate, and it is fixed on purpose
 * — a credit whose worth moved with a plan tier would be a second pricing system to keep
 * honest, and the thing being bought is identical whoever buys it.
 *
 * <h2>Why this is not the point ledger</h2>
 * Points are shop-side: earned at a kiosk, dated, expiring, and refused outright to a
 * CUSTOMER account. AI images are bought by whoever wants the picture, and after a shop
 * hands a project to a customer that is the customer — an account that can hold no points
 * at all and cannot buy a plan either. Reusing the point ledger would have meant giving
 * customers a shop currency along with the shop prices attached to it.
 *
 * <h2>Who may hold one</h2>
 * Anyone who can own a project: a RETAILER working its own rooms, a CUSTOMER working the
 * project a shop gave them, and an ADMIN. A PAINTER or DISTRIBUTOR creates no projects, so
 * a credit in their hands would have nothing to buy — they are refused BEFORE any money
 * moves rather than after, the same lesson {@link PointsPurchaseService} learned when a
 * role check placed after verification took a payment it then rolled back.
 *
 * <h2>Spending is a compare-and-set</h2>
 * Never read-then-write. A render spends on the request thread, and two tabs both reading
 * "1 credit left" must not both start a paid model call — so the balance check IS the
 * WHERE clause of the UPDATE and the database decides which one wins.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCreditService {

    private final AiCreditWalletRepository walletRepository;
    private final AiCreditTransactionRepository transactionRepository;
    private final UserRepository userRepository;

    /** Accounts that can own a project, and therefore have something to spend a credit on. */
    private static final Set<UserRole> ELIGIBLE_ROLES =
            Set.of(UserRole.RETAILER, UserRole.CUSTOMER, UserRole.ADMIN);

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Spendable credits right now. Zero for an account that has never bought any. */
    @Transactional(readOnly = true)
    public int balance(String userId) {
        return walletRepository.findByUserId(userId).map(AiCreditWallet::getBalance).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<AiCreditTransaction> recentActivity(String userId) {
        return transactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Can this account hold credits at all? The same rule {@link #spend} and the purchase
     * flow enforce, asked ahead of time so a UI can decline to offer the wallet rather than
     * showing a panel whose every button comes back 403.
     *
     * <p>Says nothing about the balance — an eligible account with none still reads true,
     * because "top up" is a real answer for it and is not one for a painter.
     */
    @Transactional(readOnly = true)
    public boolean isEligible(String userId) {
        return ELIGIBLE_ROLES.contains(roleOf(userId));
    }

    // ── Crediting ───────────────────────────────────────────────────────────

    /**
     * Credit AI images a shop or customer paid for.
     *
     * Replay protection lives with the caller, which claims the Razorpay payment id before
     * getting here — one payment credits one batch.
     */
    @Transactional
    public int creditPurchased(String userId, int credits, String paymentId) {
        requireEligible(userId);
        int balance = credit(userId, credits, AiCreditTransaction.Type.PURCHASED, paymentId,
                credits + (credits == 1 ? " AI image credit bought" : " AI image credits bought"));
        log.info("AI credits purchased: user={} credits={} payment={} balance={}",
                userId, credits, paymentId, balance);
        return balance;
    }

    /**
     * Credit AI images an administrator gave away — support, goodwill, a launch promotion.
     *
     * Deliberately a different transaction type from a purchase so the statement never
     * shows a gift where money was, and so a report of what credits actually earned can be
     * read off the journal without joining to payments.
     */
    @Transactional
    public int grant(String userId, int credits, String grantedBy, String reason) {
        requireEligible(userId);
        requirePositive(credits);
        int balance = credit(userId, credits, AiCreditTransaction.Type.GRANTED, grantedBy,
                reason == null || reason.isBlank() ? "Given by HueVista" : reason.trim());
        log.info("AI credits granted: user={} credits={} by={} balance={}",
                userId, credits, grantedBy, balance);
        return balance;
    }

    // ── Spending ────────────────────────────────────────────────────────────

    /**
     * Spend credits on one AI image.
     *
     * Throws {@link QuotaExceededException} (402) when the balance is short — the same
     * status the project-allowance gate throws, so the studio's existing "you need to pay
     * for this" branch handles both without knowing which pool came up empty.
     *
     * <p>Runs in the CALLER's transaction on purpose: whatever the credit bought is created
     * in the same unit of work, so a failure there hands the credit back by rolling the
     * spend away rather than by running a compensating refund that might not run.
     */
    @Transactional
    public void spend(String userId, int credits, String projectId, String note) {
        requireEligible(userId);
        requirePositive(credits);
        if (walletRepository.spendIfAvailable(userId, credits) == 0) {
            int held = balance(userId);
            throw new QuotaExceededException(
                    "You need " + credits + " AI image credit" + (credits == 1 ? "" : "s")
                    + " to make this image and you have " + held
                    + ". Top up your AI wallet to carry on.");
        }
        journal(userId, -credits, AiCreditTransaction.Type.SPENT_ON_RENDER, projectId, note);
        log.info("AI credits spent: user={} credits={} project={}", userId, credits, projectId);
    }

    /**
     * Hand a credit back because the image it paid for could not be made.
     *
     * The refund is the important half of a failed render. A customer who paid ₹99 for a
     * picture the model refused to produce has to be able to try again without paying
     * twice, and a wallet is exactly where that has to land — the project's own allowance
     * was already empty, which is why the credit was spent in the first place.
     *
     * <p>Never throws. It runs from the render worker's failure path, where the render is
     * already being marked FAILED, and an exception here would abandon that write and leave
     * a render stuck RUNNING for ever. Eligibility is not re-checked either: the account
     * held credits a minute ago, and a role changed in between must not swallow a refund.
     */
    @Transactional
    public void refundRender(String userId, int credits, String projectId) {
        if (userId == null || credits <= 0) {
            return;
        }
        try {
            int balance = credit(userId, credits, AiCreditTransaction.Type.RENDER_REFUNDED,
                    projectId, "Credit returned — the image could not be made");
            log.info("AI credit returned after a failed render: user={} credits={} project={} balance={}",
                    userId, credits, projectId, balance);
        } catch (RuntimeException e) {
            // Loud, because a swallowed refund is money the holder cannot see they are owed.
            log.error("Could not return an AI credit after a failed render: user={} credits={} "
                      + "project={} — {}", userId, credits, projectId, e.toString());
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Add credits and journal the movement, opening the wallet if this is the first one.
     *
     * Update-first rather than find-then-save: the UPDATE is atomic against a concurrent
     * spend, where a read entity saved back would carry a stale balance over the top of it.
     * The insert is only reached when no wallet exists at all, and a lost race on THAT
     * (two first purchases at once) is caught by the unique constraint and retried, which
     * is why the constraint is on the column and not merely in the model.
     */
    private int credit(String userId, int credits, AiCreditTransaction.Type type,
                       String reference, String note) {
        if (walletRepository.addCredits(userId, credits) == 0) {
            try {
                walletRepository.saveAndFlush(AiCreditWallet.builder()
                        .userId(userId)
                        .balance(credits)
                        .build());
            } catch (DataIntegrityViolationException raced) {
                // Somebody else opened the wallet between the UPDATE and the INSERT.
                walletRepository.addCredits(userId, credits);
            }
        }
        int balance = balance(userId);
        journalAt(userId, credits, type, reference, note, balance);
        return balance;
    }

    private void journal(String userId, int credits, AiCreditTransaction.Type type,
                         String reference, String note) {
        journalAt(userId, credits, type, reference, note, balance(userId));
    }

    private void journalAt(String userId, int credits, AiCreditTransaction.Type type,
                           String reference, String note, int balanceAfter) {
        transactionRepository.save(AiCreditTransaction.builder()
                .userId(userId)
                .credits(credits)
                .type(type)
                .reference(reference)
                .note(note)
                .balanceAfter(balanceAfter)
                .build());
    }

    private UserRole roleOf(String userId) {
        return userId == null ? null
                : userRepository.findById(userId).map(User::getRole).orElse(null);
    }

    private void requireEligible(String userId) {
        if (!ELIGIBLE_ROLES.contains(roleOf(userId))) {
            throw new SecurityException(
                    "AI image credits belong to the account that owns the room — a shop, or a "
                    + "customer working on the project their shop gave them. There would be "
                    + "nothing to spend them on here.");
        }
    }

    private static void requirePositive(int credits) {
        if (credits < 1) {
            throw new IllegalArgumentException("Credits must be a positive number.");
        }
    }
}
