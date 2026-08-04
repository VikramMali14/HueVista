package com.gridstore.huevista.library;

import java.util.Locale;

/**
 * Where the free library keeps its files, and the one rule everything else has to
 * respect about them.
 *
 * Every template photo and mask lives under {@value #PREFIX} and is shared by every
 * copy ever started from that template — the copies' rows point straight at these
 * keys rather than owning bytes of their own. That is the whole reason a template
 * costs one copy of the pixels no matter how many people open it, and it is also
 * the trap: the ordinary project code deletes a mask blob whenever a region is
 * removed, replaced, or the project is deleted. Run unguarded against a copy, the
 * first user to tidy up would delete the living room out from under everybody.
 *
 * So: {@link #isLibraryKey} marks a key as not-yours-to-delete, and every blob
 * deletion in the project code checks it first. The library service is the only
 * thing that removes these files, and only when the template itself is deleted.
 */
public final class FreeProjectStorage {

    private FreeProjectStorage() {}

    /** Storage folder for every template asset. Trailing slash included. */
    public static final String PREFIX = "free-projects/";

    /**
     * True when {@code key} names a shared library asset, which means no per-project
     * cleanup may delete it.
     *
     * Answers on the key alone and errs towards "shared" for anything malformed:
     * refusing to delete a file that turns out to be ordinary leaves a stray blob,
     * while deleting one that turns out to be shared breaks the library for everyone.
     * Accepts a bare key or a URL that contains one, since the region tables hold
     * both shapes historically.
     */
    public static boolean isLibraryKey(String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.trim().toLowerCase(Locale.ROOT);
        // Tolerate a leading slash or a full URL — the caller may hand us either.
        int idx = k.indexOf(PREFIX);
        return idx == 0 || (idx > 0 && k.charAt(idx - 1) == '/');
    }

    /** The storage folder a template's own files sit in: {@code free-projects/<slug>}. */
    public static String folderFor(String slug) {
        return PREFIX + slug;
    }
}
