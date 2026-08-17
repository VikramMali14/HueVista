package com.gridstore.huevista.account.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What moved when a kiosk guest account was folded into a real one.
 *
 * <p>Reported back in full because the customer is being asked to trust an
 * irreversible step. "Merged" on its own leaves them to go and count their rooms;
 * "2 rooms, 2 photos and 1 project allowance moved across" is the same sentence with
 * the evidence in it.
 */
@Data
@Builder
public class GuestMergeResponse {

    /** The retired account's id — kept for support, never shown to the customer. */
    private String mergedFromUserId;

    private int projectsMoved;
    private int imagesMoved;

    /** Projects the kiosk account was entitled to, added to the real account's allowance. */
    private int projectAllowanceMoved;

    /** Unspent AI image credits carried over. */
    private int aiCreditsMoved;

    /** The shop whose code the kiosk account held, for the confirmation line. */
    private String shopName;
}
