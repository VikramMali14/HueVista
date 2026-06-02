package com.gridstore.huevista.paint.model;

/** Quality band of a paint product, used for the quality/brightness indicator. */
public enum QualityTier {
    ECONOMY,
    PREMIUM,
    LUXURY;

    /** Default 1–5 brightness/quality score for a tier (the shop can override). */
    public int defaultBrightness() {
        return switch (this) {
            case ECONOMY -> 2;
            case PREMIUM -> 4;
            case LUXURY -> 5;
        };
    }
}
