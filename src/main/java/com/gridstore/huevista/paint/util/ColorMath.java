package com.gridstore.huevista.paint.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Pure hex/RGB/LRV helpers shared by the shade seeders. No Spring, no state —
 * just the colour maths used when ingesting a catalogue (RGB split + WCAG
 * Light Reflectance Value), so the logic stays identical across import paths.
 */
public final class ColorMath {

    private ColorMath() {}

    private static final Pattern SIX_DIGIT_HEX = Pattern.compile("^#?[0-9a-fA-F]{6}$");

    /** True if {@code hex} is a 6-digit colour, with or without a leading '#'. */
    public static boolean isValidHex(String hex) {
        return hex != null && SIX_DIGIT_HEX.matcher(hex).matches();
    }

    /** Upper-cases and guarantees a single leading '#'. Returns {@code null} for null input. */
    public static String normalizeHex(String hex) {
        if (hex == null) return null;
        return (hex.startsWith("#") ? hex : "#" + hex).toUpperCase();
    }

    /** Splits a hex colour into {r, g, b}. Returns {0, 0, 0} for anything shorter than 7 chars. */
    public static int[] hexToRgb(String hex) {
        if (hex == null || hex.length() < 7) return new int[]{0, 0, 0};
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[]{
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)
        };
    }

    /** WCAG relative luminance (Light Reflectance Value) on a 0–100 scale, rounded to 2 dp. */
    public static BigDecimal calculateLrv(int r, int g, int b) {
        double lrv = (0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)) * 100.0;
        return BigDecimal.valueOf(lrv).setScale(2, RoundingMode.HALF_UP);
    }

    // sRGB component (0–255) → linearized value
    private static double linearize(int c) {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
