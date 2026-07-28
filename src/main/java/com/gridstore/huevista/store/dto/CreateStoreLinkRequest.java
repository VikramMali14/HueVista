package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * The shop does not price its own kiosk link — the kiosk price is one platform-wide
 * setting ({@code app.store.price-paise}) and the whole payment is HueVista's, with the
 * shop rewarded in points per sale. All that is left to choose here is how long a
 * purchased code lasts.
 */
@Data
public class CreateStoreLinkRequest {

    /** How long each purchased code lasts. Defaults to 3 days when omitted. */
    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 14, message = "Maximum validity is 14 days")
    private Integer validDays;
}
