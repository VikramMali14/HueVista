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

        assertThat(prompt).contains("FINISH (complete unfinished / half-plastered rooms)");
        assertThat(prompt).contains("unplastered brick or blockwork");
        assertThat(prompt).contains("bare concrete ceiling soffit");
        assertThat(prompt).contains("raw cement floor");
        // The finished room must stay bare — completing plaster is not a licence to stage.
        assertThat(prompt).contains("do NOT furnish or decorate the finished room");
        // A genuine exposed-brick feature in a finished room is still a design choice.
        assertThat(prompt).contains("intentional exposed-brick");
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
