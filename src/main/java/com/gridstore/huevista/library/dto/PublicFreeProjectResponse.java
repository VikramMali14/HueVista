package com.gridstore.huevista.library.dto;

import com.gridstore.huevista.library.model.FreeProjectTemplate;
import com.gridstore.huevista.library.model.FreeProjectTemplateRegion;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;
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

    // ─── Which page this room is on ──────────────────────────────────────────
    // Sent as two booleans rather than the placement enum because that is how the
    // pages read it — /work asks "is this mine?" — and because BOTH would
    // otherwise have to be decoded by every consumer. Included on the by-slug
    // response so a room's own page can refuse a room that belongs to the other
    // surface instead of rendering it under the wrong heading.
    private boolean onGallery;
    private boolean onWork;

    // ─── Editorial copy, for /work only ──────────────────────────────────────
    // Absent on most rooms, and absent is a normal state: the portfolio page
    // omits whatever section it has nothing for rather than printing an empty
    // heading. Split here — paragraphs, label/value pairs — because this is the
    // rendering shape; the admin response keeps the raw text it edits.

    /** "Pune", "Bengaluru". */
    private String location;
    /** "2026", "Winter 2025" — free text. */
    private String projectYear;
    /** The attribution line under the story. */
    private String credit;
    /** One sentence; the card summary and the page's lead. */
    private String blurb;
    /** The story, one entry per paragraph. Empty when none was written. */
    private List<String> story;
    /** The stat row under the story. Empty when none was written. */
    private List<Stat> stats;

    @Data
    @Builder
    public static class Stat {
        private String label;
        private String value;
    }

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
                .onGallery(t.getPlacement() != null && t.getPlacement().onGallery())
                .onWork(t.getPlacement() != null && t.getPlacement().onWork())
                .location(t.getLocation())
                .projectYear(t.getProjectYear())
                .credit(t.getCredit())
                .blurb(t.getBlurb())
                .story(paragraphs(t.getStory()))
                .stats(stats(t.getStats()))
                .build();
    }

    /**
     * The story as paragraphs: split on blank lines, blanks dropped.
     *
     * Admins write this in a textarea, so it arrives with whatever spacing they
     * used — trailing newlines, a stray double return in the middle, Windows line
     * endings pasted from elsewhere. All of that has to render as prose rather
     * than as empty {@code <p>} tags, so the splitting is done once here instead
     * of being re-derived (differently) by each page that shows it.
     */
    private static List<String> paragraphs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.replace("\r\n", "\n").split("\n\\s*\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * The stat row: one {@code Label: Value} per line.
     *
     * Split on the FIRST colon only — "Photo to preview: 18 s" is the shape these
     * take, and a value is far more likely to contain a colon than a label is. A
     * line with no colon is kept as a label with no value rather than dropped,
     * because silently swallowing a line the admin typed is worse than showing it
     * half-formed where they can see and fix it.
     */
    private static List<Stat> stats(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return raw.replace("\r\n", "\n").lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    int colon = line.indexOf(':');
                    return colon < 0
                            ? Stat.builder().label(line).value("").build()
                            : Stat.builder()
                                    .label(line.substring(0, colon).trim())
                                    .value(line.substring(colon + 1).trim())
                                    .build();
                })
                .toList();
    }

    private static Colour colourOf(FreeProjectTemplateRegion r) {
        return Colour.builder()
                .label(r.getLabel())
                .hex(r.getAppliedHexCode())
                .shadeCode(r.getAppliedShadeCode())
                .build();
    }
}
