package com.gridstore.huevista.painter.model;

public enum PainterLinkStatus {
    PENDING,   // invitation generated but not yet redeemed (kept until expiry)
    ACTIVE,    // painter accepted the invitation and works with this retailer
    INACTIVE,  // retailer or painter paused the relationship
    REMOVED    // retailer or painter ended the relationship
}
