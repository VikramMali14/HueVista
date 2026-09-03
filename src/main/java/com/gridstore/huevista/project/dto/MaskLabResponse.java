package com.gridstore.huevista.project.dto;

import java.util.List;

/**
 * What one lab run produced, and what it cost to find out.
 *
 * <p>Images come back as URLs rather than bytes because the screen wants to draw
 * them over one another at full size, and because the comparison the lab is for
 * is between RUNS — keeping each run's output addressable is what lets two of
 * them sit side by side.
 *
 * @param approach   the approach that ran
 * @param model      the model it asked, or null for the approaches that ask none
 * @param ms         wall-clock time, which is part of the comparison: an exact
 *                   mask that takes 40 seconds is a different product decision
 *                   from an approximate one that takes 200 ms
 * @param canvasUrl  the uploaded image, stored so the screen and any later run
 *                   are looking at the same pixels
 * @param outputs    what came back — see {@link MaskLabOutput}
 * @param note       anything the run needs to say for itself: a model that
 *                   returned several masks, a colour that matched nothing, a
 *                   body the model rejected
 */
public record MaskLabResponse(
        MaskLabApproach approach,
        String model,
        long ms,
        String canvasUrl,
        List<MaskLabOutput> outputs,
        String note
) {

    /**
     * One image a run produced.
     *
     * @param label what it is, in words the screen can show
     * @param url   where it is
     * @param kind  how to READ it, which the screen cannot infer: a colour-coded
     *              image has to be split by category before it means anything,
     *              a binary mask is already one surface, and a raw output is
     *              whatever an unknown model chose to return
     */
    public record MaskLabOutput(String label, String url, Kind kind) {

        public enum Kind {
            /** RED/GREEN/BLUE/BLACK by category — split before use. */
            COLOUR_CODED,
            /** White is the surface, black is everything else. */
            BINARY,
            /** An unrecognised model's output, shown as-is. */
            RAW
        }
    }
}
