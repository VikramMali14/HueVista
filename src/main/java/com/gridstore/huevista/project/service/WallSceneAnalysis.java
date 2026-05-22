package com.gridstore.huevista.project.service;

import java.util.List;

/**
 * Structured output from {@link WallSceneAnalyzer}. Instead of returning a
 * text exclude list (which we then handed to grounded_sam), Claude now
 * returns image-space POINTS for each paintable surface. We feed those
 * points directly into SAM 2 to produce the actual masks — SAM 2 produces
 * far tighter boundaries than text-grounded detection, and Claude's job
 * shrinks to "where is each thing", which it does well.
 *
 * Coordinates are normalized 0..1 (matching the click-to-segment API).
 * x=0 is the left edge, y=0 is the top.
 *
 * @param paintable        whether visible walls can be repainted at all
 * @param wallMaterial     short description of the wall surface
 * @param mainWallPoints   2–4 points INSIDE the dominant paintable wall
 * @param accentWallPoints 1–3 points inside a secondary feature wall;
 *                         empty if the photo doesn't have a distinct accent
 * @param trimPoints       2–5 points on visible trim — window frames,
 *                         door frames, baseboards, fascia, balcony rails
 * @param excludePoints    2–8 points on things to EXCLUDE from every mask
 *                         (stone cladding, exposed brick, tile, doors,
 *                         windows, AC units, switches). Fed to SAM 2 as
 *                         negative points alongside each positive set.
 * @param notes            one-sentence summary surfaced to the user when
 *                         the scene is rejected as non-paintable
 */
public record WallSceneAnalysis(
        boolean paintable,
        String wallMaterial,
        List<Point> mainWallPoints,
        List<Point> accentWallPoints,
        List<Point> trimPoints,
        List<Point> excludePoints,
        String notes
) {
    public record Point(double x, double y) {
        public boolean isValid() {
            return x >= 0.0 && x <= 1.0 && y >= 0.0 && y <= 1.0;
        }
    }
}
