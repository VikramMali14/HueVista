package com.gridstore.huevista.project.dto;

import java.util.List;

/**
 * What a hand-made registration actually did.
 *
 * <p>Worth returning rather than a bare 200, because "the masks moved" and "the
 * masks you were looking at moved" are different outcomes and the bench cannot
 * tell them apart on its own. A room whose model mask carried no trim, or whose
 * accent wall was never given a region, silently registers two surfaces out of
 * three — and an admin who has just spent five minutes placing the trim by hand
 * needs to be told that it went nowhere, not shown a success tick.
 *
 * @param projectId    the room this was applied to
 * @param moved        split parts whose region was re-landed ("main", "accent", "trim")
 * @param skipped      parts that could not be, each with the reason in brackets
 * @param canvasWidth  the frame the masks were written at — the bench renders
 * @param canvasHeight against these, so a mismatch here explains a preview that
 *                     did not match the result
 * @param fit          the registration as the aligner prints it, for the log and
 *                     for the bench to show back
 */
public record MaskRegistrationResponse(
        String projectId,
        List<String> moved,
        List<String> skipped,
        int canvasWidth,
        int canvasHeight,
        String fit
) {}
