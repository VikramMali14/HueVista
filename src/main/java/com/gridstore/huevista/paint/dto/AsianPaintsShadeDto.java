package com.gridstore.huevista.paint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsianPaintsShadeDto {

    @JsonProperty("entityCode")
    private String entityCode;

    @JsonProperty("entityName")
    private String entityName;

    @JsonProperty("shadeHexCode")
    private String shadeHexCode;

    @JsonProperty("shadeFamily")
    private String shadeFamily;

    @JsonProperty("featureTag")
    private String featureTag;

    @JsonProperty("popularity")
    private String popularity;

    @JsonProperty("pageUrl")
    private String pageUrl;

    // e.g. { "color temperature": ["cool"], "tonality": ["light"], "room": ["all rooms"] }
    @JsonProperty("filterTitle")
    private Map<String, List<String>> filterTitle;
}
