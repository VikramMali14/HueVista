package com.gridstore.huevista.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Freeze one of the admin's own segmented projects into the free library.
 *
 * The project supplies the pixels — its photo and its already-generated masks are
 * copied once into the shared folder — and this supplies the shelf it goes on.
 */
@Data
public class PublishTemplateRequest {

    @NotBlank(message = "Pick the project to publish")
    private String projectId;

    @NotBlank(message = "Give the template a title")
    @Size(max = 160, message = "Title is too long")
    private String title;

    /** INTERIOR or EXTERIOR. */
    @NotNull(message = "Choose interior or exterior")
    private String space;

    /** "LIVING_ROOM", "KITCHEN", "TRADITIONAL"… — uppercase, underscores. */
    @NotBlank(message = "Choose a room type")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$", message = "Room type must be uppercase letters, digits and underscores")
    private String roomKey;

    /** Optional override for how the room type reads; defaults to a title-cased key. */
    @Size(max = 80)
    private String roomLabel;

    /**
     * Optional. Defaults to a slug derived from the title, with a numeric suffix if
     * that is taken, so publishing twice never collides.
     */
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Slug may contain lowercase letters, digits and single hyphens")
    @Size(max = 120)
    private String slug;

    private String description;

    private Integer displayOrder;

    /** Defaults to true — a template is published to be used. */
    private Boolean published;

    /**
     * Which public page it goes on: GALLERY, WORK or BOTH.
     * Defaults to {@link com.gridstore.huevista.library.model.TemplatePlacement#DEFAULT}.
     */
    private String placement;

    // ─── Editorial copy for the "Our work" page ──────────────────────────────
    // Optional. Ignored by the gallery grid, which reads everything it shows off
    // the room itself; the portfolio page uses what is here and omits what isn't.

    @Size(max = 120, message = "Location is too long")
    private String location;

    @Size(max = 16, message = "Year is too long")
    private String projectYear;

    @Size(max = 200, message = "Credit line is too long")
    private String credit;

    @Size(max = 400, message = "Blurb is too long — it is one sentence on a card")
    private String blurb;

    /** Paragraphs separated by blank lines. */
    private String story;

    /** One {@code Label: Value} per line. */
    private String stats;
}
