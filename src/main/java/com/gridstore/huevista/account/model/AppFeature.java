package com.gridstore.huevista.account.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * A page (or tool) a distributor can switch on or off for one of its shops.
 *
 * The account hierarchy already lets a distributor decide which paint companies a
 * shop may work with; this is the same idea applied to the product surface — some
 * distributors sell their shops the full workspace, others only want them handing
 * out customer codes. Rather than bolt a boolean onto {@link Organization} per page
 * (a schema change every time a page ships), the grantable set is enumerated here
 * and stored as rows in {@code retailer_feature_assignments}.
 *
 * <p>Only pages that are genuinely optional appear here. The dashboard, the account
 * settings and the subscription/plan page are deliberately NOT grantable: a shop
 * that cannot reach its own billing page can never fix a lapsed subscription, and a
 * shop with no dashboard has nowhere to land after signing in. Revoking those would
 * produce an account that cannot be recovered without an admin.
 *
 * <p>{@link #getPath()} is the frontend route the feature guards, and it is the
 * contract between the two repositories — the Next.js nav and page guards look the
 * feature up by this path.
 */
public enum AppFeature {

    STUDIO("Studio", "/atelier",
            "Upload a room photograph and repaint it shade by shade."),
    COLOR_FINDER("Colour finder", "/color-finder",
            "Pull the nearest catalogue shade codes out of any photograph."),
    CATALOGUE("Catalogue", "/catalogue",
            "Browse, compare and favourite shades across the assigned companies."),
    PRODUCTS("Products", "/products",
            "Maintain the shop's own product list and pricing."),
    CUSTOMER_PORTAL("Customer portal", "/portal",
            "Issue customer access codes and follow the projects they create."),
    NETWORK("My network", "/network",
            "Create painter accounts and read the shop's downline report.");

    private final String label;
    private final String path;
    private final String description;

    AppFeature(String label, String path, String description) {
        this.label = label;
        this.path = path;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    /** The frontend route this feature gates, e.g. {@code /color-finder}. */
    public String getPath() {
        return path;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Lenient lookup for request payloads — case-insensitive, and tolerant of the
     * hyphenated spelling the frontend uses in URLs ({@code color-finder}). Unknown
     * names come back empty rather than throwing, so one stale key in a saved
     * selection can't fail the whole request.
     */
    public static Optional<AppFeature> fromKey(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().replace('-', '_').toUpperCase();
        return Arrays.stream(values()).filter(f -> f.name().equals(key)).findFirst();
    }
}
