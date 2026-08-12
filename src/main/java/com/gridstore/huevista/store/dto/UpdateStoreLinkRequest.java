package com.gridstore.huevista.store.dto;

import lombok.Data;

/**
 * Partial update — only the provided fields change.
 *
 * Pausing is the only thing here. Neither the kiosk price nor the window on a
 * purchased code is the shop's to set: both are platform-wide, so the shop's controls
 * on a link are pause, resume and delete.
 */
@Data
public class UpdateStoreLinkRequest {

    /** false pauses the kiosk and keeps the printed URL; true brings it back. */
    private Boolean active;
}
