package com.gridstore.huevista.project.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards on a registration a person placed by hand, rather than one the
 * search measured.
 *
 * <p>Everything {@link MaskAligner} produces on its own is safe by construction
 * — every node capped, then smoothed, then scored — so none of these cases can
 * arise from the automatic path. They arise from the align bench, where somebody
 * can drag one lattice node clean across its neighbour, and the damage is
 * invisible afterwards: the stored PNG decodes perfectly and simply has a wall
 * in it twice, with a tear down the middle. So the checks are asserted here
 * rather than left to the UI that is supposed to prevent them.
 */
class ManualRegistrationTest {

    /** A lattice with every node at rest — the shape a valid one has. */
    private static double[] flat(int cols, int rows) {
        return new double[(cols + 1) * (rows + 1)];
    }

    @Test
    void acceptsARegistrationThatOnlyShiftsAndScales() {
        assertThatCode(() -> MaskAligner.Fit.manual(1.03, 1.03, -0.018, 0.024, null))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsALatticeWhoseNodesMoveGentlyApart() {
        int cols = 4, rows = 6;
        double[] du = flat(cols, rows);
        double[] dv = flat(cols, rows);
        // A tenth of a cell's width between neighbours: a real local drift, and
        // nowhere near the fold.
        for (int j = 0; j <= rows; j++) {
            for (int i = 0; i <= cols; i++) {
                du[j * (cols + 1) + i] = i * (0.1 / cols);
            }
        }
        assertThatCode(() -> MaskAligner.Warp.of(cols, rows, du, dv)).doesNotThrowAnyException();
    }

    @Test
    void rejectsALatticeThatFoldsHorizontally() {
        int cols = 4, rows = 4;
        double[] du = flat(cols, rows);
        double[] dv = flat(cols, rows);
        // Two adjacent nodes a full cell-width apart: past this the resampler's
        // map stops being a function of the canvas and the mask doubles back.
        du[1] = 1.0 / cols;

        assertThatThrownBy(() -> MaskAligner.Warp.of(cols, rows, du, dv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("folds horizontally");
    }

    @Test
    void rejectsALatticeThatFoldsVertically() {
        int cols = 4, rows = 4;
        double[] du = flat(cols, rows);
        double[] dv = flat(cols, rows);
        dv[cols + 1] = 1.0 / rows;

        assertThatThrownBy(() -> MaskAligner.Warp.of(cols, rows, du, dv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("folds vertically");
    }

    @Test
    void rejectsALatticeWhoseArraysDoNotMatchItsGrid() {
        assertThatThrownBy(() -> MaskAligner.Warp.of(4, 4, new double[10], new double[10]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must hold 25 nodes");
    }

    @Test
    void rejectsANodeThatIsNotAFiniteNumber() {
        int cols = 2, rows = 2;
        double[] du = flat(cols, rows);
        double[] dv = flat(cols, rows);
        du[4] = Double.NaN;

        assertThatThrownBy(() -> MaskAligner.Warp.of(cols, rows, du, dv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void rejectsAScaleThatWouldResizeTheDrawingIntoADifferentPicture() {
        assertThatThrownBy(() -> MaskAligner.Fit.manual(3.0, 1, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scaleX");
    }

    @Test
    void rejectsAnOffsetThatWouldThrowTheMaskOffTheFrame() {
        assertThatThrownBy(() -> MaskAligner.Fit.manual(1, 1, 0, 0.9, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offsetY");
    }

    /**
     * The point of all of the above: a lattice that passes the guard resamples
     * into a mask that still has one of each wall in it.
     *
     * <p>A folded map is not a decode error and not an empty mask — it is a mask
     * that looks fine and has the wall twice — so the check is that the block
     * comes back as ONE run of foreground across the row it was drawn on.
     */
    @Test
    void anAcceptedLatticeResamplesWithoutDoublingTheWall() throws Exception {
        int w = 240, h = 180;
        BufferedImage block = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 40; y < 140; y++) {
            for (int x = 60; x < 180; x++) {
                block.setRGB(x, y, 0xFFFFFF);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(block, "png", png);

        int cols = 4, rows = 4;
        double[] du = flat(cols, rows);
        double[] dv = flat(cols, rows);
        // A steep ramp — a third of the frame of stretch across it — centred so no
        // single node spends more than half the absolute cap getting there. The
        // per-step difference is 0.075 of the frame against a fold limit of 0.225,
        // so this is a hard warp that is still comfortably a function.
        for (int j = 0; j <= rows; j++) {
            for (int i = 0; i <= cols; i++) {
                du[j * (cols + 1) + i] = ((double) i / cols - 0.5) * 0.3;
            }
        }
        MaskAligner.Warp warp = MaskAligner.Warp.of(cols, rows, du, dv);

        byte[] out = MaskProcessor.resizeBinaryAligned(png.toByteArray(), w, h, 1, 1, 0, 0, warp);
        BufferedImage landed = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(landed).isNotNull();

        int runs = 0;
        boolean inRun = false;
        int y = h / 2;
        for (int x = 0; x < w; x++) {
            boolean on = (landed.getRGB(x, y) & 0xff) > 127;
            if (on && !inRun) runs++;
            inRun = on;
        }
        assertThat(runs)
                .as("a wall that appears twice across one row is a folded map")
                .isEqualTo(1);
    }
}
