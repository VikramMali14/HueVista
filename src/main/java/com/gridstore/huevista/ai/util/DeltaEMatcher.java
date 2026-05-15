package com.gridstore.huevista.ai.util;

import com.gridstore.huevista.paint.model.Shade;

import java.util.List;

/**
 * Nearest-shade matching using CIE76 Delta E in CIELAB color space.
 * Lower Delta E = more similar colors. Human eye can't distinguish Delta E < 1.
 */
public final class DeltaEMatcher {

    private DeltaEMatcher() {}

    public static Shade findNearest(String targetHex, List<Shade> catalog) {
        double[] targetLab = hexToLab(targetHex);
        Shade best = null;
        double bestDelta = Double.MAX_VALUE;

        for (Shade shade : catalog) {
            if (shade.getRgbR() == null) continue;
            double[] lab = rgbToLab(shade.getRgbR(), shade.getRgbG(), shade.getRgbB());
            double delta = deltaE(targetLab, lab);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = shade;
            }
        }
        return best;
    }

    public static double computeDeltaE(String hex1, String hex2) {
        return deltaE(hexToLab(hex1), hexToLab(hex2));
    }

    // ── sRGB hex → CIELAB ────────────────────────────────────────────────────

    private static double[] hexToLab(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        return rgbToLab(r, g, b);
    }

    private static double[] rgbToLab(int r, int g, int b) {
        double[] xyz = rgbToXyz(r, g, b);
        return xyzToLab(xyz[0], xyz[1], xyz[2]);
    }

    private static double[] rgbToXyz(int r, int g, int b) {
        double rLin = linearize(r / 255.0);
        double gLin = linearize(g / 255.0);
        double bLin = linearize(b / 255.0);

        // sRGB D65 matrix
        double x = rLin * 0.4124564 + gLin * 0.3575761 + bLin * 0.1804375;
        double y = rLin * 0.2126729 + gLin * 0.7151522 + bLin * 0.0721750;
        double z = rLin * 0.0193339 + gLin * 0.1191920 + bLin * 0.9503041;
        return new double[]{x, y, z};
    }

    private static double linearize(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double[] xyzToLab(double x, double y, double z) {
        // D65 white point
        double fx = labF(x / 0.95047);
        double fy = labF(y / 1.00000);
        double fz = labF(z / 1.08883);

        double L = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double bVal = 200.0 * (fy - fz);
        return new double[]{L, a, bVal};
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    private static double deltaE(double[] lab1, double[] lab2) {
        double dL = lab1[0] - lab2[0];
        double da = lab1[1] - lab2[1];
        double db = lab1[2] - lab2[2];
        return Math.sqrt(dL * dL + da * da + db * db);
    }
}
