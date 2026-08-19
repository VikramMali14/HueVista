package com.gridstore.huevista.project.service;

import com.gridstore.huevista.image.model.HouseType;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.project.model.CleanAngle;
import com.gridstore.huevista.project.model.CleanFurnishing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADMIN prompt knobs — and, first and above everything else, the guarantee that
 * leaving them alone changes nothing.
 *
 * <h2>Why this file exists</h2>
 *
 * Each non-default option works by SWAPPING a named passage of a 300-line prompt
 * constant for a different one. That is the right mechanism — appending "now move the
 * camera" under twenty lines insisting the camera does not move produces a coin flip,
 * not an instruction — but it has one failure mode, and it is a nasty one: edit the base
 * prompt, and the anchor silently stops matching. The option still appears in the
 * studio, the run still costs a full generation, and the prompt it actually sent was the
 * default one. Nothing at runtime looks wrong.
 *
 * <p>So every swap is asserted here to actually change the prompt. Editing a base prompt
 * without updating its anchor is meant to break this build, which is the only place it
 * can be caught cheaply.
 */
class CleanPromptOptionsTest {

    // ── The guarantee ────────────────────────────────────────────────────────

    /**
     * The property the whole feature rests on: these are an admin testing surface, and a
     * customer's run must be unable to tell they exist.
     */
    @ParameterizedTest
    @EnumSource(ImageType.class)
    void defaultsReproduceTheOriginalPromptByteForByte(ImageType scene) {
        assertThat(ImageCleanerService.cleanPromptFor(
                        scene, HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.AS_SHOT))
                .isEqualTo(ImageCleanerService.cleanPromptFor(scene));
    }

    /** Nulls arrive from a project whose columns were never written. They mean "default". */
    @ParameterizedTest
    @EnumSource(ImageType.class)
    void nullOptionsAreTreatedAsDefaults(ImageType scene) {
        assertThat(ImageCleanerService.cleanPromptFor(scene, null, null, null))
                .isEqualTo(ImageCleanerService.cleanPromptFor(scene));
    }

    @Test
    void promptOptionsDefaultIsActuallyTheDefault() {
        assertThat(ImageCleanerService.PromptOptions.DEFAULT.isDefault()).isTrue();
        assertThat(new ImageCleanerService.PromptOptions(null, null, null))
                .isEqualTo(ImageCleanerService.PromptOptions.DEFAULT);
        assertThat(new ImageCleanerService.PromptOptions(
                HouseType.BATHROOM, CleanFurnishing.KEEP, CleanAngle.AS_SHOT).isDefault())
                .isFalse();
    }

    // ── Furnishing: EMPTY ────────────────────────────────────────────────────

    /**
     * The rule that had to GO, not just be argued with. Left standing, "ALL furniture …
     * stays exactly where it is" sits in KEEP UNCHANGED contradicting the instruction to
     * clear the room, and the model picks whichever it likes.
     */
    @Test
    void emptyingARoomRemovesTheRuleThatKeepsTheFurniture() {
        String kept = interior(HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);
        String emptied = interior(HouseType.UNKNOWN, CleanFurnishing.EMPTY, CleanAngle.AS_SHOT);

        assertThat(kept).contains("ALL furniture already in the room");
        assertThat(emptied).doesNotContain("ALL furniture already in the room");
        assertThat(emptied).contains("CLEAR THE LOOSE FURNITURE");
        // Every swap fired: nothing about the "same furniture" summary survives either.
        assertThat(emptied).doesNotContain("same contents, same furniture");
        assertThat(emptied).doesNotContain("the existing sofa, bed, table, cabinet");
    }

    /**
     * Emptying a room is licence to take things away and NOTHING else. The block
     * forbidding new objects is the only thing between us and a model that reads "clear
     * the furniture" as "show me a nicer version of this room".
     */
    @Test
    void emptyingARoomIsNeverALicenceToStageIt() {
        String emptied = interior(HouseType.UNKNOWN, CleanFurnishing.EMPTY, CleanAngle.AS_SHOT);

        assertThat(emptied).contains("DO NOT ADD ANYTHING");
        assertThat(emptied).contains("Do NOT 'stage', 'style', 'decorate', 'furnish'");
        assertThat(emptied).contains("The room is EMPTIED, not restyled");
        // What is fixed stays fixed — this is emptying a room, not refurbishing one.
        assertThat(emptied).contains("built-in cabinetry and wardrobes");
    }

    @Test
    void clearingAnExteriorFrontageWidensTheExistingRemoveRule() {
        String cleared = exterior(HouseType.UNKNOWN, CleanFurnishing.EMPTY, CleanAngle.AS_SHOT);

        assertThat(cleared).contains("clear the frontage completely");
        assertThat(cleared).contains("Permanent landscaping, paving and boundary walls stay");
    }

    // ── Angle: BEST_VIEW ─────────────────────────────────────────────────────

    /**
     * Three passages pin the camera independently, and any one left standing turns
     * BEST_VIEW into a contradiction rather than an instruction. All three must go.
     */
    @Test
    void bestViewRemovesEveryRuleThatPinsTheCamera() {
        String asShot = interior(HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);
        String reframed = interior(HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.BEST_VIEW);

        assertThat(asShot).contains("Camera angle, perspective, framing, image dimensions");
        assertThat(asShot).contains("Do NOT re-light, re-frame, re-render or re-photograph");
        assertThat(asShot).contains("Camera position, framing, aspect and image dimensions are");

        assertThat(reframed).doesNotContain("Camera angle, perspective, framing, image dimensions");
        assertThat(reframed).doesNotContain("Do NOT re-light, re-frame, re-render or re-photograph");
        assertThat(reframed).doesNotContain("Camera position, framing, aspect and image dimensions are");
        assertThat(reframed).doesNotContain("must come back unchanged.");
    }

    @Test
    void bestViewRemovesTheExteriorCameraRulesToo() {
        String asShot = exterior(HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);
        String reframed = exterior(HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.BEST_VIEW);

        assertThat(asShot).contains("preserve the exact perspective");
        assertThat(asShot).contains("Do NOT re-light, re-frame or re-render the scene");
        assertThat(asShot).contains("pixel-faithful");

        assertThat(reframed).doesNotContain("preserve the exact perspective");
        assertThat(reframed).doesNotContain("Do NOT re-light, re-frame or re-render the scene");
        assertThat(reframed).doesNotContain("pixel-faithful");
    }

    /**
     * "Best angle" with no bound is how a model ends up drawing a side elevation nobody
     * has ever photographed, on a canvas somebody is about to pick paint from. Every one
     * of these limits is load-bearing.
     */
    @ParameterizedTest
    @EnumSource(ImageType.class)
    void bestViewIsBoundedTightly(ImageType scene) {
        String reframed = ImageCleanerService.cleanPromptFor(
                scene, HouseType.UNKNOWN, CleanFurnishing.KEEP, CleanAngle.BEST_VIEW);

        assertThat(reframed).contains("VIEWPOINT");
        assertThat(reframed).contains("SAME ELEVATION, SAME SIDE");
        assertThat(reframed).contains("REVEAL NOTHING NEW");
        assertThat(reframed).contains("SAME STANDING HEIGHT");
        assertThat(reframed).contains("WHEN IN DOUBT, DO NOT MOVE");
        // The aspect pin matters downstream: the masks are sized against the ORIGINAL
        // photo's dimensions, so a canvas that comes back a different shape misaligns
        // everything drawn over it.
        assertThat(reframed).contains("aspect ratio and image dimensions exactly as the input");
    }

    /**
     * Both options edit the closing summary, which names the contents and the framing in
     * one sentence. Applied twice, the second swap would find its anchor already gone —
     * so it is written once, from both answers.
     */
    @Test
    void emptyAndBestViewTogetherStillRewriteTheClosingSummaryOnce() {
        String both = interior(HouseType.UNKNOWN, CleanFurnishing.EMPTY, CleanAngle.BEST_VIEW);

        assertThat(both).contains("OUTPUT: the SAME room — the loose furniture cleared, "
                + "the fixed fittings kept, re-framed per VIEWPOINT —");
        assertThat(both).doesNotContain("same contents, same furniture");
    }

    // ── House type ───────────────────────────────────────────────────────────

    @Test
    void unknownHouseTypeAddsNothingAtAll() {
        assertThat(ImageCleanerService.houseTypeClause(HouseType.UNKNOWN, true)).isEmpty();
        assertThat(ImageCleanerService.houseTypeClause(null, false)).isEmpty();
    }

    /**
     * The FINISH rules are written for a wall that should end up smooth plaster, and in a
     * bathroom that is exactly backwards — the tile IS the finish. This clause is the
     * whole reason house type is worth detecting.
     */
    @Test
    void bathroomProtectsTheTileFromTheFinishRules() {
        String prompt = interior(HouseType.BATHROOM, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);

        assertThat(prompt).contains("THE TILE IS A FINISH, NOT UNFINISHED WORK");
        assertThat(prompt).contains("PLASTERED wall above the tile line");
    }

    @Test
    void shopfrontKeepsItsSignage() {
        String prompt = exterior(HouseType.SHOPFRONT, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);

        assertThat(prompt).contains("SIGNAGE IS PERMANENT");
        assertThat(prompt).contains("never repainted the wall");
    }

    @Test
    void compoundWallIsToldItHasNoRoofToPreserve() {
        String prompt = exterior(HouseType.COMPOUND_WALL, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);

        assertThat(prompt).contains("BOUNDARY or COMPOUND WALL, not a building");
        assertThat(prompt).contains("no windows and no interior");
    }

    @Test
    void rowHouseIsToldNotToPaintTheNeighbours() {
        String prompt = exterior(HouseType.ROW_HOUSE, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);

        assertThat(prompt).contains("ONE UNIT IN A TERRACE");
        assertThat(prompt).contains("paint up to it, never across it");
    }

    @Test
    void apartmentBlockIsToldToCountItsFloors() {
        String prompt = exterior(HouseType.APARTMENT_BLOCK, CleanFurnishing.KEEP, CleanAngle.AS_SHOT);

        assertThat(prompt).contains("COUNT THE FLOORS");
    }

    /**
     * A clause is only worth its tokens if it names a failure the base prompt walks into.
     * The two types the base prompts were already written for get nothing, and that is
     * the correct answer rather than an omission.
     */
    @Test
    void theTypesTheBasePromptsAlreadyHandleGetNoClause() {
        assertThat(ImageCleanerService.houseTypeClause(HouseType.INDEPENDENT_HOUSE, false)).isEmpty();
        assertThat(ImageCleanerService.houseTypeClause(HouseType.LIVING_ROOM, true)).isEmpty();
        assertThat(ImageCleanerService.houseTypeClause(HouseType.BEDROOM, true)).isEmpty();
    }

    /** No type may ever throw — an unhandled member would fail a paid run. */
    @ParameterizedTest
    @EnumSource(HouseType.class)
    void everyHouseTypeProducesAPromptForEitherScene(HouseType type) {
        assertThat(interior(type, CleanFurnishing.KEEP, CleanAngle.AS_SHOT)).isNotBlank();
        assertThat(exterior(type, CleanFurnishing.KEEP, CleanAngle.AS_SHOT)).isNotBlank();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String interior(HouseType t, CleanFurnishing f, CleanAngle a) {
        return ImageCleanerService.cleanPromptFor(ImageType.INDOOR, t, f, a);
    }

    private static String exterior(HouseType t, CleanFurnishing f, CleanAngle a) {
        return ImageCleanerService.cleanPromptFor(ImageType.OUTDOOR, t, f, a);
    }
}
