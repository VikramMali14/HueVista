package com.gridstore.huevista.project.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One finished room, as the AI-images page offers it when somebody wants another picture.
 *
 * <p>Deliberately not {@link ProjectSummaryResponse}. That one describes a room to work in
 * — its status, its regions, its access window, who its shop is — and none of that is a
 * reason to pick one here. This carries the three things the choice is actually made on:
 * what the room is called, what it looks like, and how many combinations are waiting
 * inside it. Sending the larger shape would also mean sending a room's whole commercial
 * position to a screen that only needs a thumbnail.
 *
 * <p>Both photographs travel, because choosing between them is the next step: the cleaned
 * one the pipeline produced and the original that was taken. {@code cleanedImageUrl} is
 * null on a room that never got one, which is what the picker reads to know the choice is
 * not offered rather than offered and quietly ignored.
 */
@Data
@Builder
public class RenderableProjectResponse {

    private String id;
    private String name;
    private String roomType;

    /** The photograph as it was taken. Always present — a project cannot exist without it. */
    private String imageUrl;

    /**
     * The cleaned photograph: clutter gone, paintable surfaces flattened to white.
     *
     * Null when the room has none, and that null is load-bearing — it is the difference
     * between "pick which picture to paint" and a choice with one real option in it.
     */
    private String cleanedImageUrl;

    /** When the job finished. Never null here: only closed rooms are offered. */
    private LocalDateTime closedAt;

    /** How many colour-board combinations this room can be photographed in. Never zero:
     *  a room with nothing to render is not offered. */
    private int comboCount;
}
