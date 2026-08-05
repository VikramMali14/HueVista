package com.gridstore.huevista.library.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A template's slug becomes a storage FOLDER, so it has to survive whatever an
 * admin types in the title box without ever escaping the library prefix.
 */
class FreeProjectSlugTest {

    @Test
    void lowercasesAndHyphenates() {
        assertThat(FreeProjectLibraryService.slugify("Sunlit Living Room")).isEqualTo("sunlit-living-room");
        assertThat(FreeProjectLibraryService.slugify("Kitchen  —  North light")).isEqualTo("kitchen-north-light");
    }

    @Test
    void stripsAnythingThatCouldClimbOutOfTheFolder() {
        assertThat(FreeProjectLibraryService.slugify("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(FreeProjectLibraryService.slugify("a/../b")).isEqualTo("a-b");
        assertThat(FreeProjectLibraryService.slugify("..")).isEqualTo("room");
    }

    @Test
    void neverReturnsBlankOrEdgeHyphens() {
        assertThat(FreeProjectLibraryService.slugify("")).isEqualTo("room");
        assertThat(FreeProjectLibraryService.slugify(null)).isEqualTo("room");
        assertThat(FreeProjectLibraryService.slugify("!!!")).isEqualTo("room");
        assertThat(FreeProjectLibraryService.slugify("  spaced  ")).isEqualTo("spaced");
    }

    @Test
    void capsLengthWithoutLeavingATrailingHyphen() {
        String slug = FreeProjectLibraryService.slugify("x".repeat(80) + " " + "y".repeat(80));
        assertThat(slug.length()).isLessThanOrEqualTo(100);
        assertThat(slug).doesNotEndWith("-");
    }

    /**
     * The edges used to be trimmed with an anchored {@code -+$}, which backtracks
     * quadratically over a long run of hyphens. Separator-only input is the worst
     * case: every character is a candidate and none of them survives.
     */
    @Test
    void separatorOnlyTitlesAreLinear() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(FreeProjectLibraryService.slugify("-".repeat(200_000))).isEqualTo("room");
            assertThat(FreeProjectLibraryService.slugify(" ".repeat(200_000))).isEqualTo("room");
            // A run that ends in something keepable — the case the old regex scanned
            // from every position before giving up.
            assertThat(FreeProjectLibraryService.slugify("-".repeat(200_000) + "a")).isEqualTo("a");
        });
    }

    @Test
    void collapsesInternalRunsToASingleHyphen() {
        assertThat(FreeProjectLibraryService.slugify("a" + "-".repeat(500) + "b")).isEqualTo("a-b");
        assertThat(FreeProjectLibraryService.slugify("Wall   ///   Trim")).isEqualTo("wall-trim");
    }
}
