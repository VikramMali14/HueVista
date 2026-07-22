package com.gridstore.huevista.paint.model;

/** Quality band of a paint product, used for the quality/brightness indicator. */
public enum QualityTier {
    ECONOMY,
    PREMIUM,
    LUXURY;

    /** Default 1–10 brightness/quality score for a tier (the shop can override). */
    public int defaultBrightness() {
        return switch (this) {
            case ECONOMY -> 4;
            case PREMIUM -> 8;
            case LUXURY -> 10;
        };
    }
}
