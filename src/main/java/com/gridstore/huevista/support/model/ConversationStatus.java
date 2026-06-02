package com.gridstore.huevista.support.model;

public enum ConversationStatus {
    /** The AI agent is handling it. */
    OPEN,
    /** Escalated — waiting for a human agent. */
    NEEDS_HUMAN,
    /** Closed. */
    RESOLVED
}
