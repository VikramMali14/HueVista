package com.gridstore.huevista.lead.dto;

import lombok.Data;

/**
 * An admin approving a shop-account request. The only decision left is which
 * distributor the shop belongs under — everything else came from the owner.
 */
@Data
public class ApproveShopRequestRequest {

    /**
     * The distributor org to file the new shop under. Blank means the house
     * distributor ("HueVista Direct"), which is also what the 24-hour deadline uses,
     * so a shop is never left outside the network.
     */
    private String distributorOrgId;
}
