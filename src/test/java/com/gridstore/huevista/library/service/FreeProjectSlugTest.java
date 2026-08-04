package com.gridstore.huevista.library.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
