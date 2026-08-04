package com.gridstore.huevista.library.model;

/**
 * Which half of the free-project library a template belongs to. Interiors are
 * grouped by room (living room, kitchen, hall…); exteriors by style (traditional,
 * modern…). Both use the same {@code roomKey} column — the gallery groups by
 * space first, so the two vocabularies never collide.
 */
public enum TemplateSpace {
    INTERIOR,
    EXTERIOR
}
