package com.gridstore.huevista.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that keeps the free library from being deleted by the people using it.
 *
 * Projects started from a template share the template's photo and masks, so the
 * ordinary per-project cleanup — which deletes a mask blob whenever a wall is
 * removed, replaced, or the project is deleted — must recognise a shared key and
 * leave it alone. Getting this wrong loses the room for everyone holding a copy,
 * which is why the cases below include the awkward shapes the region columns
 * actually hold: bare keys on new rows, relative paths and presigned URLs on old.
 */
class FreeProjectStorageTest {

    @Test
    void recognisesBareLibraryKeys() {
        assertThat(FreeProjectStorage.isLibraryKey("free-projects/sunlit-living-room/photo.jpg")).isTrue();
        assertThat(FreeProjectStorage.isLibraryKey("free-projects/kitchen-01/mask-0.png")).isTrue();
    }

    @Test
    void recognisesLibraryKeysInsideUrls() {
        // Local storage hands back a relative API path…
        assertThat(FreeProjectStorage.isLibraryKey("/api/images/files/free-projects/hall-02/mask-1.png")).isTrue();
        // …and S3 a presigned absolute one.
        assertThat(FreeProjectStorage.isLibraryKey(
                "https://bucket.s3.ap-south-1.amazonaws.com/free-projects/villa-03/mask-2.png?X-Amz-Signature=abc"))
                .isTrue();
    }

    @Test
    void leavesOrdinaryProjectKeysDeletable() {
        assertThat(FreeProjectStorage.isLibraryKey("11111111-1111-1111-1111-111111111111/abc.png")).isFalse();
        assertThat(FreeProjectStorage.isLibraryKey("/api/images/files/2222/mask.png")).isFalse();
    }

    @Test
    void doesNotMatchOnAPartialFolderName() {
        // Must be a whole path segment — a user folder that merely starts the same
        // way is ordinary, deletable data.
        assertThat(FreeProjectStorage.isLibraryKey("myfree-projects/x.png")).isFalse();
        assertThat(FreeProjectStorage.isLibraryKey("free-projects-backup/x.png")).isFalse();
    }

    @Test
    void blanksAreNotLibraryKeys() {
        assertThat(FreeProjectStorage.isLibraryKey(null)).isFalse();
        assertThat(FreeProjectStorage.isLibraryKey("")).isFalse();
        assertThat(FreeProjectStorage.isLibraryKey("   ")).isFalse();
    }

    @Test
    void folderForNamesTheTemplatesOwnSubfolder() {
        assertThat(FreeProjectStorage.folderFor("sunlit-living-room")).isEqualTo("free-projects/sunlit-living-room");
        // And whatever it returns must itself read back as library-owned.
        assertThat(FreeProjectStorage.isLibraryKey(
                FreeProjectStorage.folderFor("kitchen-01") + "/mask-0.png")).isTrue();
    }
}
