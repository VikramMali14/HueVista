package com.gridstore.huevista.project.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Ends renders that can no longer end by themselves, and hands the allowance back.
 *
 * <p>{@link ProjectRenderService} spends the allowance up front and refunds it on failure,
 * which is the right trade — it is what stops two tabs starting two ₹99 renders on the same
 * included one. But it only holds while every render reaches a terminal state, because the
 * refund lives on the failure path. A render that reaches neither READY nor FAILED is the
 * one shape that breaks the promise: the customer has paid, has no image, and nothing will
 * ever come back to say so.
 *
 * <p>Two things strand a render that way, and neither is hypothetical. A process that dies
 * mid-render — a deploy, an OOM, a restart — leaves it RUNNING with nobody holding it. And
 * until the fix that landed alongside this class, every accepted render was handed to the
 * worker before its own transaction had committed, so the worker found no row, logged
 * "Render vanished before it ran", and stopped: QUEUED for ever, paid for, silent.
 *
 * <p>That second cause is fixed at the source and this sweeper is not what makes renders
 * work. It is what makes the money right anyway when they don't — including for the renders
 * already stranded in the database by the time this deploys, which no amount of correct
 * dispatch reaches backwards to save.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRenderSweeper {

    private final ProjectRenderService renderService;

    /**
     * How long a render may go without reaching a terminal state before it is presumed dead.
     *
     * <p>Generous on purpose. A render itself is bounded at about three minutes by the
     * model poll, but a queued one waits behind whatever else is on the AI executor, and
     * failing a render that was merely going to be slow would refund a customer whose image
     * then arrives — the one outcome worse than being late. Thirty minutes is far past any
     * real backlog on a pool of sixteen and still same-session for someone waiting.
     */
    @Value("${app.render.stranded-after-minutes:30}")
    private long strandedAfterMinutes;

    /** What the owner is told. Deliberately the same promise the other failure paths make. */
    private static final String MESSAGE =
            "Your image could not be made — it stopped before it finished. Your credit is "
            + "back, please try again.";

    /**
     * Every ten minutes: often enough that a customer watching the page sees an answer
     * rather than a spinner, rare enough to be a single indexed query most of the time.
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void run() {
        try {
            // Never below the model's own ceiling, whatever the property says — a
            // misconfigured five would fail renders that are still legitimately running.
            Duration grace = Duration.ofMinutes(Math.max(10, strandedAfterMinutes));
            List<String> stranded = renderService.strandedRenderIds(grace);
            if (stranded.isEmpty()) return;

            log.warn("Renders stranded past {} minutes, failing and refunding: count={}",
                    grace.toMinutes(), stranded.size());
            // One transaction each, through the proxy: fail() is REQUIRES_NEW, so a row
            // that will not settle costs its own render and not the rest of the batch.
            for (String renderId : stranded) {
                try {
                    renderService.fail(renderId, MESSAGE);
                } catch (Exception e) {
                    log.warn("Could not fail a stranded render: render={} reason={}",
                            renderId, e.getMessage());
                }
            }
        } catch (Exception e) {
            // Bookkeeping: a failed sweep must never take the scheduler down with it.
            log.warn("Stranded render sweep failed: {}", e.getMessage());
        }
    }
}
