package com.gridstore.huevista.project.service;

import java.util.List;

/**
 * Structured output from {@link WallSceneAnalyzer} — what Claude saw in the
 * uploaded photograph, used to drive Grounded SAM with image-specific
 * prompts instead of a static one-size-fits-all list.
 *
 * @param paintable       whether visible walls are a paintable surface
 *                        (painted plaster/drywall/concrete). False for brick,
 *                        tile, marble, wallpaper, wood paneling, vinyl siding.
 * @param wallMaterial    short human-readable description of the wall surface
 *                        (e.g. "painted plaster", "exposed brick"). May be null
 *                        when paintable is true and the material is unremarkable.
 * @param excludeObjects  every non-paintable object visible in the photo, as
 *                        lowercase noun phrases ("wooden door", "ceiling fan",
 *                        "wall-mounted television"). Fed directly to Grounded
 *                        SAM as the negative_mask_prompt.
 * @param notes           one-sentence summary surfaced to the user when the
 *                        scene is rejected as non-paintable.
 */
public record WallSceneAnalysis(
        boolean paintable,
        String wallMaterial,
        List<String> excludeObjects,
        String notes
) {
}
