package com.gridstore.huevista.painter.model;

public enum PaintJobStatus {
    NEW,          // retailer assigned, painter has not responded
    ACCEPTED,     // painter accepted; quote + schedule set
    DECLINED,     // painter declined; retailer may reassign
    IN_PROGRESS,  // painter has started work
    COMPLETED,    // painter marked work complete
    CANCELLED     // retailer or customer cancelled
}
