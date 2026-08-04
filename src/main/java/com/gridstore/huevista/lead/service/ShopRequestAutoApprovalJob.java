package com.gridstore.huevista.lead.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opens the shop accounts nobody got round to.
 *
 * <p>A shop that filled the form and confirmed its email has done everything asked of
 * it; making it wait on an admin being awake is the platform's problem, not theirs.
 * After 24 hours the request provisions itself — free plan, house distributor, the
 * same account the one-click button would have made. An admin who gets there first
 * simply finds nothing left to do.
 *
 * <p>Runs hourly rather than by the minute: the deadline is a promise of "within a
 * day", not a stopwatch, and an hourly sweep keeps the query cheap.
 *
 * <p>The loop lives here, in a bean of its own, on purpose. Each request has to be
 * provisioned in its own transaction, and a service calling its own {@code
 * @Transactional} method would bypass the proxy and put the whole batch in one — where
 * a single failure marks the transaction rollback-only and takes every later request
 * with it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopRequestAutoApprovalJob {

    private final ShopLeadService leadService;

    @Scheduled(cron = "0 30 * * * *") // hourly, at :30 past the hour
    public void sweep() {
        try {
            provisionOverdueRequests();
        } catch (Exception e) {
            // Never let a scheduled sweep die: the next hour must still run.
            log.error("Shop-request auto-approval sweep failed: {}", e.getMessage(), e);
        }
    }

    /** @return how many accounts were created */
    public int provisionOverdueRequests() {
        int created = 0;
        for (String leadId : leadService.overdueRequestIds()) {
            try {
                leadService.provisionOverdue(leadId);
                created++;
            } catch (Exception e) {
                // One shop whose address was taken in the meantime, or that an admin
                // dealt with mid-sweep, must not stop the rest.
                log.warn("Auto-approval of shop request {} failed: {}", leadId, e.getMessage());
            }
        }
        if (created > 0) {
            log.info("Auto-approval created {} shop account(s) past the 24-hour deadline", created);
        }
        return created;
    }
}
