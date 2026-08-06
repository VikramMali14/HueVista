package com.gridstore.huevista.siteasset;

import java.util.Locale;

/**
 * Where the marketing site's editable images live, and the one rule the rest of
 * the system has to respect about them.
 *
 * Files sit under {@value #PREFIX}, addressed by slot rather than by owner. They
 * differ from every other upload in one way that matters: they are served to
 * anonymous visitors, because the pages that show them (the home page, the
 * method page) have no session. The public file route therefore serves keys
 * under this prefix and nothing else — {@link #isSiteAssetKey} is what it asks —
 * so a crafted key can never walk out of here into a user's folder.
 *
 * Deliberately NOT exempt from the platform reset. The reset purges the object
 * store wholesale and clears every table that names a key; leaving these files
 * behind while their rows went would point the home page at images that are no
 * longer there. After a reset the site draws its built-in defaults again, which
 * is a working state — a half-cleared one would not be.
 */
public final class SiteAssetStorage {

    private SiteAssetStorage() {}

    /** Storage folder for every site asset. No trailing slash: it is passed where
     *  the storage service expects an owner prefix and appends the slash itself. */
    public static final String FOLDER = "site-assets";

    /** The same folder as a key prefix, for matching stored keys. */
    public static final String PREFIX = FOLDER + "/";

    /**
     * True when {@code key} names a site asset and is safe to hand to storage.
     *
     * Storage keys here are always "site-assets/{uuid}.{ext}". Anything carrying
     * "..", a backslash, a NUL or a leading slash is an attempted traversal —
     * rejected before the prefix is even considered, because
     * "site-assets/../<userId>/<uuid>.jpg" both starts with the prefix and
     * escapes it.
     */
    public static boolean isSiteAssetKey(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.contains("..")
                || key.contains("\\")
                || key.indexOf('\0') >= 0
                || key.startsWith("/")) {
            return false;
        }
        return key.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }
}
