package com.gridstore.huevista.library.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One of the admin's own projects, offered as the source for a new template.
 * Only projects that already carry walls can be published — the whole promise of
 * the library is that the masks exist before anyone opens the room.
 */
@Data
@Builder
public class PublishableProjectResponse {
    private String id;
    private String name;
    private String roomType;
    private String status;
    private String imageUrl;
    private int regionCount;
    /** False when it has no masks yet; the picker shows it greyed out and says why. */
    private boolean eligible;
    private String ineligibleReason;
    private LocalDateTime updatedAt;
}
