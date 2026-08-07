package com.gridstore.huevista.library.dto;

import com.gridstore.huevista.library.model.FreeProjectTemplate;
import com.gridstore.huevista.library.model.FreeProjectTemplateRegion;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A published room as the PUBLIC gallery shows it.
 *
 * Deliberately not {@link FreeProjectTemplateResponse}. That one is the admin's
 * view and carries things a visitor has no business seeing: the mask URLs, the id
 * of the admin project it was frozen from, how many copies are alive on its files.
 * None of it is secret exactly, and all of it is either an internal handle or an
 * operational number — so the public shape is written out separately rather than
 * trimmed at the edge, where the next field added to the admin DTO would quietly
 * appear on the marketing site.
 *
 * What a gallery card actually needs is here: the photograph, what the room is,
 * and the colours on its walls.
 */
@Data
@Builder
public class PublicFreeProjectResponse {

    /** Stable, human-readable handle — the gallery's own URL segment. */
    private String slug;
    private String title;
    private String description;
    /** INTERIOR or EXTERIOR. */
    private String space;
    /** "Living room", "Façade" — what the gallery groups and labels by. */
    private String roomLabel;
    private String imageUrl;
    private Integer imageWidth;
    private Integer imageHeight;
    /** How many surfaces are painted in the picture. */
    private int wallCount;
    /** The colours on those surfaces, in wall order — the card's swatch row. */
    private List<Colour> colours;
    /** When it was published; the gallery prints the month. */
    private LocalDateTime publishedAt;

    @Data
    @Builder
    public static class Colour {
        /** "Main wall", "Trim" — named for the person reading the card. */
        private String label;
        private String hex;
        /** The catalogue code, when the wall was painted from a real shade. */
        private String shadeCode;
    }

    public static PublicFreeProjectResponse from(FreeProjectTemplate t, String imageUrl) {
        List<Colour> colours = t.getRegions().stream()
                .filter(r -> r.getAppliedHexCode() != null && !r.getAppliedHexCode().isBlank())
                .map(PublicFreeProjectResponse::colourOf)
                .toList();
        return PublicFreeProjectResponse.builder()
                .slug(t.getSlug())
                .title(t.getTitle())
                .description(t.getDescription())
                .space(t.getSpace().name())
                .roomLabel(t.getRoomLabel())
                .imageUrl(imageUrl)
                .imageWidth(t.getImageWidth())
                .imageHeight(t.getImageHeight())
                .wallCount(t.getRegions().size())
                .colours(colours)
                .publishedAt(t.getCreatedAt())
                .build();
    }

    private static Colour colourOf(FreeProjectTemplateRegion r) {
        return Colour.builder()
                .label(r.getLabel())
                .hex(r.getAppliedHexCode())
                .shadeCode(r.getAppliedShadeCode())
                .build();
    }
}
