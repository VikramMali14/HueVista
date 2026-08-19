package com.gridstore.huevista.project.service;

import com.gridstore.huevista.image.model.ImageType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two prompt rules that make an under-construction room usable.
 *
 * A room photographed mid-build (raw brick walls, bare concrete ceiling, cement
 * floor) used to come out unusable end to end: the interior clean prompt had no
 * FINISH section, so the walls came back bare brick, and the mask prompt's
 * unconditional "brick is cladding → BLACK" rule then blacked out every wall —
 * no main wall, so the whole segmentation run failed. Both halves are asserted
 * here because either one alone leaves the flow broken.
 */
class CleaningAndMaskPromptTest {

    @Test
    void interiorCleanPromptCompletesUnfinishedSurfaces() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).contains("FINISH (mandatory for EVERY unfinished surface, judged surface by");
        assertThat(prompt).contains("unplastered brick or blockwork");
        assertThat(prompt).contains("bare concrete ceiling soffit");
        assertThat(prompt).contains("raw cement floor");
        // The finished room must stay bare — completing plaster is not a licence to stage.
        assertThat(prompt).contains("do NOT furnish or decorate the finished room");
        // A genuine exposed-brick feature in a finished room is still a design choice.
        assertThat(prompt).contains("intentional exposed-brick");
    }

    /**
     * The first fix got the walls white but left them whitewashed BRICK — the model
     * recoloured the masonry instead of resurfacing it. Painting over the texture has
     * to be named as the failure it is, or it stays the cheapest way to satisfy
     * "paint the wall white".
     */
    @Test
    void cleanPromptsDemandResurfacingNotJustRecolouring() {
        for (ImageType scene : new ImageType[]{ImageType.INDOOR, ImageType.OUTDOOR}) {
            String prompt = ImageCleanerService.cleanPromptFor(scene);

            assertThat(prompt).contains("RESURFACE, DO NOT JUST RECOLOUR");
            assertThat(prompt).containsIgnoringCase("IS NOT FINISHING IT");
            assertThat(prompt).contains("mortar joint");
            assertThat(prompt).containsIgnoringCase("whitewashed brick");
        }
    }

    /**
     * The ceiling came back raw grey concrete because FINISH was one buried line while
     * the prompt opened with "DO NOT ADD ANYTHING (most important rule)" and closed with
     * "an under-edited photo is always better". Finishing now has equal billing at the
     * top, and the closing caution is scoped to clutter so it stops suppressing it.
     */
    @Test
    void interiorCleanPromptGivesFinishingEqualBillingWithRestraint() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).contains("YOU HAVE TWO JOBS, AND BOTH ARE REQUIRED");
        assertThat(prompt).contains("Do not let the restraint of job (1) talk you out of job (2)");
        assertThat(prompt).contains("CEILING — FINISH IT TOO; DO NOT SKIP IT");
        assertThat(prompt).contains("shuttering plank lines");
        assertThat(prompt).contains("SELF-CHECK");
        // The "leave it alone" tie-breaker must be scoped to clutter in BOTH prompts,
        // so it can never be read as licence to skip an unfinished surface.
        for (ImageType scene : new ImageType[]{ImageType.INDOOR, ImageType.OUTDOOR}) {
            assertThat(ImageCleanerService.cleanPromptFor(scene))
                    .contains("Unfinished construction surfaces are always completed.")
                    .doesNotContain("something counts as clutter, LEAVE IT AS IT IS");
        }
    }

    /**
     * A room can be finished everywhere EXCEPT the ceiling — smooth painted walls
     * under a bare concrete slab is the common half-built case. FINISH used to be
     * gated on the room being "a construction shell", which let the model look at
     * finished walls, conclude the room was not a shell, and skip the ceiling. The
     * gate is now per-surface, so walls/ceiling/floor are judged one at a time.
     */
    @Test
    void interiorCleanPromptJudgesEachSurfaceIndependently() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).contains("ASSESS WALLS, CEILING AND FLOOR INDEPENDENTLY");
        assertThat(prompt).contains("Finishing is per-surface, not per-room");
        // The exact wrong inference has to be named, or it stays available.
        assertThat(prompt).contains("this room already looks finished, so there is nothing");
        assertThat(prompt).contains("walls are already smooth and painted but whose "
                + "ceiling is still raw grey concrete is the COMMON case");
        // The old all-or-nothing shell gate must be gone from the job list too.
        assertThat(prompt).doesNotContain("if this room is still a construction shell");
    }

    /**
     * The ceiling must land at the same premium standard as the walls and read as
     * one surface with them — a ceiling that is merely "not concrete any more",
     * or that meets the wall in a visible tonal step, still looks unfinished.
     */
    @Test
    void interiorCleanPromptDemandsPremiumSeamlessWhiteOnWallsAndCeiling() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).contains("COMPLETELY PLASTERED, PUTTIED TO A SMOOTH FINISH, "
                + "AND PAINTED A MATCHING FLAWLESS MATTE WHITE");
        assertThat(prompt).contains("CONTINUOUS AND SEAMLESS WITH THE WALLS");
        assertThat(prompt).contains("no colour break, no tonal step, no visible seam");
        assertThat(prompt).contains("FLAWLESS, SEAMLESS MATTE WHITE WALLS");
        assertThat(prompt).contains("professional plastering, fine puttying and top-tier matte paint");
        // Already-finished walls must not be degraded on the way through.
        assertThat(prompt).contains("must come back at this same "
                + "flawless standard");
    }

    /**
     * Context the room needs to read as a real place: the view out of the windows
     * and the dark timber joinery. Both were getting flattened — the view whited
     * out, the door absorbed into the surrounding white.
     */
    @Test
    void interiorCleanPromptPreservesViewAndDarkWoodJoinery() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).contains("THE VIEW OUTSIDE");
        assertThat(prompt).contains("urban "
                + "skyline, neighbouring buildings, rooftops");
        assertThat(prompt).contains("Do not blank it out, white it out, fog it, blur it");
        assertThat(prompt).contains("THE DARK WOOD JOINERY stays dark wood");
        assertThat(prompt).contains("never whitened, lightened or");
        // Both are re-checked before the image comes back.
        assertThat(prompt).contains("the dark wood door is still dark");
    }

    /**
     * A wall can be flat, even and smoothly plastered and still be nowhere near
     * finished — in most of these photos the plaster is done and the putty coat and
     * paint are not. That wall fell between the two rules that were supposed to catch
     * it: REPAINT covered "every PAINTED wall", so it excluded a wall nobody had ever
     * painted, and FINISH listed only rough masonry — raw brick, bare cement render,
     * patchy half-applied plaster — so a wall with no roughness left to see matched
     * nothing. The room came back with its bare grey plaster faithfully preserved,
     * which is the one reading of "change nothing you were not told to change" that
     * makes the visualiser useless.
     *
     * Both halves are asserted: the trigger has to name the state, and the rule has to
     * say what to do about it (putty, then paint).
     */
    @Test
    void interiorCleanPromptPuttiesAndPaintsAPlasteredButUnpaintedWall() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).contains("A WALL IS ONLY FINISHED ONCE IT IS PLASTERED, PUTTIED AND PAINTED");
        assertThat(prompt).contains("WALLS THAT ARE PLASTERED BUT NOT YET PUTTIED OR PAINTED");
        // The wrong inference is the mirror of the room-level one, so it is named too.
        assertThat(prompt).contains("SMOOTH IS NOT FINISHED");
        assertThat(prompt).contains("apply the putty coat if it does not have one");
        // REPAINT must not exclude a wall that was never painted in the first place.
        assertThat(prompt).contains("repaint EVERY wall a single even coat")
                .doesNotContain("repaint every painted wall a single even coat");
        // ...and an unpainted surface must not slip through as a "finished choice".
        assertThat(prompt).contains("unpainted plaster is a stage of the work, not a chosen");
        // Checked once more before the image is returned.
        assertThat(prompt).contains("NO SURFACE IS LEFT UNPAINTED");
    }

    /**
     * The interior prompt names its colours in WORDS, and stays short enough to read.
     *
     * Both halves are the point of this prompt's rewrite, and both are the kind of
     * property that erodes one well-meant edit at a time: somebody puts {@code #ffffff}
     * back "for precision", somebody else restates an existing rule "to be safe", and it
     * is fourteen thousand characters of partly self-contradicting instruction again.
     * An image model has never seen a hex code on a paint tin — it has seen ten million
     * captions saying "white wall" — and the instruction it follows worst is the one it
     * has to read three times in three different phrasings.
     *
     * <p>The exterior prompt still uses hex, and is asserted here so the split reads as
     * a decision rather than a half-finished edit.
     */
    @Test
    void interiorCleanPromptNamesItsColoursInWordsAndStaysShort() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.INDOOR);

        assertThat(prompt).doesNotContainPattern("#[0-9a-fA-F]{6}");
        assertThat(prompt).contains("clean, bright, pure brilliant white")
                .contains("dark wood brown")
                .contains("charcoal grey");
        // "whitewash" is the name of this prompt's worst failure — white paint with the
        // brick pattern still legible under it — so it must never become the verb for
        // the instruction as well. Every occurrence is that failure, and no other.
        assertThat(prompt.replace("whitewashed brick", "")).doesNotContain("whitewash");
        // A guard rail, not a target: well under it is fine, creeping back over it is
        // the regression. The prompt was 14,108 characters before the rewrite.
        assertThat(prompt.length()).isLessThan(12_000);

        assertThat(ImageCleanerService.cleanPromptFor(ImageType.OUTDOOR)).contains("#ffffff");
    }

    /** The same gap, and the same fix, on a facade left in bare plaster. */
    @Test
    void exteriorCleanPromptPuttiesAndPaintsAPlasteredButUnpaintedWall() {
        String prompt = ImageCleanerService.cleanPromptFor(ImageType.OUTDOOR);

        assertThat(prompt).contains("A WALL IS ONLY FINISHED ONCE IT IS PLASTERED, PUTTIED AND PAINTED");
        assertThat(prompt).contains("repaint EVERY wall a single even coat")
                .doesNotContain("repaint EVERY painted wall a single even coat");
        assertThat(prompt).contains("PUTTIED AND PAINTED — never left as bare plaster or bare putty");
    }

    @Test
    void exteriorCleanPromptStillUsedForOutdoorAndUnknown() {
        assertThat(ImageCleanerService.cleanPromptFor(ImageType.OUTDOOR))
                .isEqualTo(ImageCleanerService.cleanPromptFor(ImageType.UNKNOWN))
                .contains("photograph of a house")
                .contains("FINISH (complete unfinished / half-plastered walls)");
    }

    @Test
    void maskPromptTreatsConstructionMasonryAsPaintableWall() {
        for (boolean interior : new boolean[]{true, false}) {
            String prompt = ReplicateMaskSegmenter.colorCodedPrompt(interior);

            assertThat(prompt).contains("BARE CONSTRUCTION MASONRY IS A WALL, NOT CLADDING");
            // The exception has to come with the test that separates the two cases,
            // or the model just swaps one blanket rule for another.
            assertThat(prompt).contains("DECORATIVE cladding (BLACK) sits in an otherwise FINISHED space");
            assertThat(prompt).contains("CONSTRUCTION masonry (RED/GREEN) is a shell still being built");
            // Cladding in a finished space stays excluded from the paint masks.
            assertThat(prompt).contains("CLADDING IS A MATERIAL, NOT PAINT");
            // The all-black output that started this is called out as a failure.
            assertThat(prompt).contains("The image is NOT mostly black");
        }
    }

    @Test
    void maskPromptKeepsOpeningsOutOfTheTrimMask() {
        String prompt = ReplicateMaskSegmenter.colorCodedPrompt(true);

        assertThat(prompt).contains("AN OPENING IS NOT TRIM");
        assertThat(prompt).contains("its void is black");
    }

    @Test
    void maskPromptFillsInteriorWallsCornerToCorner() {
        String prompt = ReplicateMaskSegmenter.colorCodedPrompt(true);

        assertThat(prompt).contains("EVERY room wall plane");
        assertThat(prompt).contains("returns/reveals inside a deep opening");
        // Only the accent paragraph varies by scene — interiors always get one.
        assertThat(prompt).contains("EXACTLY ONE");
        assertThat(ReplicateMaskSegmenter.colorCodedPrompt(false))
                .contains("ZERO OR ONE, DECIDED BY ARCHITECTURAL MERIT");
    }
}
