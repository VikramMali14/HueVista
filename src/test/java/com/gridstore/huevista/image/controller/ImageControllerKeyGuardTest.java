package com.gridstore.huevista.image.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the storage-key access-control guard used by serveFile().
 * The legacy {@code startsWith(userId + "/")} check was bypassable with "../";
 * these lock in that traversal is now rejected.
 */
class ImageControllerKeyGuardTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";

    @Test
    void allowsOwnCleanKey() {
        assertThat(ImageController.isOwnedKey(USER + "/abc-123.jpg", USER)).isTrue();
        assertThat(ImageController.isOwnedKey(USER + "/sub/abc.png", USER)).isTrue();
    }

    @Test
    void rejectsTraversalIntoAnotherUser() {
        String other = "22222222-2222-2222-2222-222222222222";
        // Starts with "<me>/" but climbs out to another user's dir.
        assertThat(ImageController.isOwnedKey(USER + "/../" + other + "/secret.jpg", USER)).isFalse();
    }

    @Test
    void rejectsTraversalOutOfStorageRoot() {
        assertThat(ImageController.isOwnedKey(USER + "/../../../../etc/passwd", USER)).isFalse();
    }

    @Test
    void rejectsAbsoluteBackslashAndNul() {
        assertThat(ImageController.isOwnedKey("/etc/passwd", USER)).isFalse();
        assertThat(ImageController.isOwnedKey(USER + "\\..\\x", USER)).isFalse();
        assertThat(ImageController.isOwnedKey(USER + "/x\0.jpg", USER)).isFalse();
    }

    @Test
    void rejectsAnotherUsersDirectAccessAndBlanks() {
        assertThat(ImageController.isOwnedKey("33333333/abc.jpg", USER)).isFalse();
        assertThat(ImageController.isOwnedKey("", USER)).isFalse();
        assertThat(ImageController.isOwnedKey(null, USER)).isFalse();
    }

    // ─── Free-project library ────────────────────────────────────────────────
    // Library assets belong to no one and are readable by any signed-in caller —
    // a project started from a template points straight at these keys, so without
    // the allowance the room 403s for the person who just opened it.

    @Test
    void allowsFreeLibraryAssets() {
        assertThat(ImageController.isFreeLibraryKey("free-projects/sunlit-living-room/abc.jpg")).isTrue();
        assertThat(ImageController.isFreeLibraryKey("free-projects/kitchen-01/mask-2.png")).isTrue();
    }

    @Test
    void freeLibraryAllowanceStillRejectsTraversal() {
        assertThat(ImageController.isFreeLibraryKey("free-projects/../" + USER + "/private.jpg")).isFalse();
        assertThat(ImageController.isFreeLibraryKey("/free-projects/x.jpg")).isFalse();
        assertThat(ImageController.isFreeLibraryKey("free-projects\\x.jpg")).isFalse();
        assertThat(ImageController.isFreeLibraryKey("free-projects/x\0.jpg")).isFalse();
    }

    @Test
    void freeLibraryAllowanceDoesNotLeakOtherFolders() {
        // A user folder that merely starts with the same letters is not the library.
        assertThat(ImageController.isFreeLibraryKey("free-projects-backup/x.jpg")).isFalse();
        assertThat(ImageController.isFreeLibraryKey(USER + "/free-projects/x.jpg")).isFalse();
        assertThat(ImageController.isFreeLibraryKey("")).isFalse();
        assertThat(ImageController.isFreeLibraryKey(null)).isFalse();
    }
}
