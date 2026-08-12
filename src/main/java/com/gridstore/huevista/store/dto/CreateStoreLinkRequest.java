package com.gridstore.huevista.store.dto;

import lombok.Data;

/**
 * Nothing to choose.
 *
 * A kiosk link belongs to the shop and never expires; the shop ends it by pausing or
 * deleting it, not by a clock. The price is one platform-wide setting
 * ({@code app.store.price-paise}) because the whole payment is HueVista's, and the
 * window on the code a walk-in buys is a platform default too — it was the one field
 * left here, and it described the CODE rather than the link, which is why creating a
 * permanent link asked for "3, 7 or 14 days" beside counter-issued codes running a
 * fixed 10.
 *
 * The body is kept, and empty, so a client that still posts a validity gets a link
 * rather than a 400. The value is ignored.
 */
@Data
public class CreateStoreLinkRequest {
}
