package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for folding a kiosk guest account into the signed-in account.
 *
 * <p>The kiosk account's own session token is the authorisation — the caller proves
 * they are holding the browser the purchase was made in. The printed access code is
 * deliberately NOT accepted here: it is shown to shop staff and printed on a slip, so
 * accepting it would let anyone who saw one take the room it bought.
 */
@Data
public class MergeGuestAccountRequest {

    @NotBlank
    private String guestToken;
}
