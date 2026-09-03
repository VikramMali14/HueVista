package com.gridstore.huevista.project.dto;

import lombok.Data;

import java.util.List;

/**
 * One run of the mask lab: which way of producing a mask to try, and what to
 * give it.
 *
 * <p>The lab exists because the pipeline's mask comes from a GENERATIVE model,
 * which repaints the photo rather than tracing it — so its colour blocks land a
 * few percent off the surfaces they describe, and everything downstream
 * ({@code MaskAligner}, the align bench) is correction for that. The question
 * the lab is built to answer is whether some other way of producing the mask
 * avoids the problem instead of correcting it, and the only honest way to answer
 * it is to run them on the same photograph and look.
 *
 * <p>The fields are a union: each approach reads the ones that mean something to
 * it and ignores the rest. That is deliberate — a per-approach request type per
 * approach would be four DTOs to keep in step with one screen, and the screen
 * already knows which fields it is sending.
 */
@Data
public class MaskLabRequest {

    /** Which approach to run. See {@link MaskLabApproach}. */
    private MaskLabApproach approach = MaskLabApproach.GENERATIVE;

    /**
     * GENERATIVE and CUSTOM_REPLICATE: the Replicate model to ask.
     *
     * <p>For GENERATIVE this is checked against {@code AiModelCatalogue} like
     * every other admin model pin, so nothing outside the allow-list reaches
     * Replicate. For CUSTOM_REPLICATE it deliberately is NOT — the whole point
     * of that approach is trying a model nobody has added yet.
     */
    private String model;

    /** GENERATIVE: INDOOR forces an accent surface, OUTDOOR does not. Anything
     *  unrecognised is treated as OUTDOOR, which is what a facade is. */
    private String scene;

    /**
     * CUSTOM_REPLICATE: the model's input body as JSON, with {@code {{image}}}
     * wherever the uploaded image's URL should go.
     *
     * <p>Free text rather than a typed shape because the models worth trying
     * here do not agree on one: a semantic segmenter wants
     * {@code {"image": "..."}}, a text-grounded one wants a prompt beside it, and
     * a SAM variant wants point arrays. Anything typed would be a guess at
     * schemas that change per model, and would have to be redeployed every time
     * somebody wanted to try a new one.
     */
    private String inputTemplate;

    /** SAM_POINTS: click positions, normalised 0–1 against the uploaded image.
     *  Converted to pixels here, because that is what SAM 2 takes. */
    private List<List<Double>> points;

    /** SAM_POINTS: 1 to include a point's surface, 0 to push the boundary off it.
     *  Defaults to all-inclusive when absent or the wrong length. */
    private List<Integer> pointLabels;

    /**
     * PAINTED_SURFACE: how far a pixel may sit from the repaint colour and still
     * count as that surface, 0–255 per channel.
     *
     * <p>Not zero, and it cannot be: the clean is told to keep each surface's
     * light and shade ("recolour the surfaces — do not flatten them into a solid
     * sticker of colour"), so a white wall in the cleaned photo runs from bright
     * highlight to deep shadow and only its HUE is uniform.
     */
    private Integer tolerance;

    /** PAINTED_SURFACE: drop blobs smaller than this share of the frame, 0–1.
     *  A facade's paintable surfaces are large; specks this size are JPEG noise
     *  and single bright roof tiles. */
    private Double minBlobShare;
}
