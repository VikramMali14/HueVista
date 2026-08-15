package com.gridstore.huevista.library.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Edit a room that is already on the shelf, without touching its pixels.
 *
 * Everything here is metadata — where the room shows, what it is called, the
 * story printed beside it. The photograph and the masks are not editable from
 * here on purpose: those come from the source project and are replaced by
 * {@code POST /{id}/refresh}, which is the one path allowed to move a wall.
 *
 * <p><strong>Null means "leave it alone"; empty string means "clear it".</strong>
 * That distinction is the whole reason this is a PATCH body of boxed types rather
 * than a full replacement — an admin editing the story of one room must not have
 * to resend its location to keep it, and an admin deleting a credit line needs a
 * way to say so that is not indistinguishable from not mentioning it.
 */
@Data
public class UpdateTemplateRequest {

    @Size(max = 160, message = "Title is too long")
    private String title;

    /** INTERIOR or EXTERIOR. */
    private String space;

    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$", message = "Room type must be uppercase letters, digits and underscores")
    private String roomKey;

    @Size(max = 80)
    private String roomLabel;

    private String description;

    private Integer displayOrder;

    /** GALLERY, WORK or BOTH. */
    private String placement;

    // ─── Editorial copy for the "Our work" page ──────────────────────────────

    @Size(max = 120, message = "Location is too long")
    private String location;

    @Size(max = 16, message = "Year is too long")
    private String projectYear;

    @Size(max = 200, message = "Credit line is too long")
    private String credit;

    @Size(max = 400, message = "Blurb is too long — it is one sentence on a card")
    private String blurb;

    /** Paragraphs separated by blank lines. See PublishTemplateRequest on the cap. */
    @Size(max = 8000, message = "The story is too long")
    private String story;

    /** One {@code Label: Value} per line. */
    @Size(max = 2000, message = "That is a lot of numbers — keep it to a few lines")
    private String stats;
}
