package com.gridstore.huevista.paint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsianPaintsApiResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("shade")
    private List<AsianPaintsShadeDto> shade;
}
