package com.gridstore.huevista.support.model;

public enum MessageSender {
    /** The customer or retailer who opened the conversation. */
    USER,
    /** The AI support agent. */
    AI,
    /** A human support agent. */
    AGENT,
    /** System notices (e.g. "escalated to a human"). */
    SYSTEM
}
