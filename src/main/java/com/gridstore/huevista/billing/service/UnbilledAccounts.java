package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accounts that RUN the platform rather than buy from it.
 *
 * Today that is exactly one role — ADMIN — but the question "is this account billed?"
 * was previously asked in five places by five different means, and each of them got it
 * subtly wrong for an administrator:
 *
 * <ul>
 *   <li>{@code /billing/subscriptions/current} 404'd, because an admin has no
 *       subscription row and never will.</li>
 *   <li>{@code /billing/pdf-allowance} 402'd, for the same reason.</li>
 *   <li>Project creation, PDF downloads and the AI gates all asked for a plan an admin
 *       is not supposed to hold.</li>
 * </ul>
 *
 * The console then rendered payment prompts to the person who administers the payments.
 * Giving an admin a real subscription row would have fixed the symptom and created a
 * worse problem — a comped plan that expires, renews, and shows up in revenue reports —
 * so the answer is to state the exemption once, here, and have every gate consult it.
 *
 * <p>This is deliberately NOT "admins get the Enterprise plan". There is no plan, no
 * period, and nothing to renew; the gates simply do not apply. Anything that reports on
 * money continues to see no subscription for these accounts, which is the truth.
 */
@Service
@RequiredArgsConstructor
public class UnbilledAccounts {

    private final UserRepository userRepository;

    /**
     * True when this account is exempt from every billing gate.
     *
     * An id that matches no user reads as billed — the safe direction. The alternative
     * hands full unmetered access to any caller whose account has since been deleted.
     */
    @Transactional(readOnly = true)
    public boolean covers(String userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(User::getRole)
                .filter(UnbilledAccounts::isUnbilled)
                .isPresent();
    }

    /** Role-only variant, for the call sites that already hold the user. */
    public static boolean isUnbilled(UserRole role) {
        return role == UserRole.ADMIN;
    }
}
