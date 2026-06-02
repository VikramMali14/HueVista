package com.gridstore.huevista.support.model;

/** Where a support conversation originated. Adapters (WhatsApp, voice, email) all
 *  funnel into the same Conversation model. */
public enum SupportChannel {
    IN_APP,
    WHATSAPP,
    VOICE,
    EMAIL
}
