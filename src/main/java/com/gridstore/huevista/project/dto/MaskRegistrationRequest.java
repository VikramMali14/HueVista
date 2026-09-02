package com.gridstore.huevista.project.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Where an admin decided the model's colour-coded mask actually belongs on the
 * canvas, as placed by hand in the align bench.
 *
 * <p>This is a REGISTRATION, not a mask: it says where to put the drawing the
 * model already made, and carries no pixels of its own. The backend re-splits
 * the stored raw mask through it and replaces the detected regions' stored
 * masks — so the shapes stay exactly what detection found, and only their
 * placement changes. A mask whose SHAPE is wrong is a different job, and the
 * Mask Studio's brush is the tool for it.
 *
 * <p>Geometry matches {@code MaskAligner.Fit} exactly, because it becomes one.
 * Forward, in each axis, both frames normalised to 0..1 of their own size:
 * <pre>
 *   u_canvas = 0.5 + (u_mask − 0.5) · scale + offset   [ + warp at that point ]
 * </pre>
 * which the resampler runs backwards. Offsets and warp displacements are shares
 * of the frame, not pixels, so a registration measured on a preview stays
 * correct when it is applied at full canvas resolution.
 */
@Data
public class MaskRegistrationRequest {

    /** Whole-frame size correction about the centre. 1 leaves the size alone. */
    private double scaleX = 1;
    private double scaleY = 1;

    /** Whole-frame shift, in shares of the frame. Positive moves the mask right
     *  and down. */
    private double offsetX = 0;
    private double offsetY = 0;

    /**
     * Columns and rows of the local lattice, when the frame needed more than one
     * rigid answer — a facade whose parapet drifted up while its boundary wall
     * drifted down has no single scale and offset that lands both.
     *
     * <p>Null (or absent) means a purely rigid registration, which is the common
     * case and the one to prefer: a lattice is only worth its resample when the
     * drift genuinely differs across the frame.
     */
    @Min(value = 1, message = "warpCols must be at least 1")
    @Max(value = 64, message = "warpCols may not exceed 64")
    private Integer warpCols;

    @Min(value = 1, message = "warpRows must be at least 1")
    @Max(value = 64, message = "warpRows may not exceed 64")
    private Integer warpRows;

    /**
     * The lattice itself: {@code (warpCols + 1) × (warpRows + 1)} displacements
     * in row-major order, node {@code (i,j)} sitting at {@code u = i/cols,
     * v = j/rows} of the canvas and holding the extra nudge applied there on top
     * of the rigid part. Interpolated bilinearly between nodes, so the
     * correction is continuous and neighbouring regions cannot be pulled apart
     * into an unpainted seam.
     *
     * <p>Rejected if it folds — see {@code MaskAligner.Warp.of}.
     */
    private double[] warpDu;
    private double[] warpDv;

    /**
     * What the person was looking at when they placed this, free text from the
     * bench ("parapet was 2% high, boundary wall 3% low"). Carried into the log
     * line, so a registration that later looks strange can be read alongside the
     * reason it was made rather than guessed at.
     */
    private String note;

    /** True when a lattice was sent as well as the rigid part. */
    public boolean hasWarp() {
        return warpCols != null && warpRows != null && warpDu != null && warpDv != null;
    }
}
