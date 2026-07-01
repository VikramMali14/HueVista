package com.gridstore.huevista.paint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * One shade in a public bulk-upload JSON array. Only {@code code}, {@code name} and
 * {@code hex} are required; the rest are optional metadata. Unknown fields are ignored
 * so a slightly richer export still uploads cleanly.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShadeUploadItem {

    /** Unique shade code within the company, e.g. "9436". Doubles as the idempotency key. */
    private String code;

    /** Display name, e.g. "Air Breeze". */
    private String name;

    /** 6-digit hex colour, with or without '#', e.g. "#F3EDE8". */
    private String hex;

    /** Shade family, e.g. "Off Whites", "Blues". */
    private String family;

    /** cool / warm / neutral. */
    private String colorTemperature;

    /** light / medium / dark. */
    private String tonality;

    /** A highlight tag, e.g. "Recommended", "Colour of the year". */
    private String featureTag;

    /** Rank within the catalogue (lower = more popular). */
    private Integer popularity;

    /** Canonical product page URL. */
    private String pageUrl;

    /** Rooms this shade suits, e.g. ["living room", "bedroom"]. */
    private List<String> suitableRooms;
}
