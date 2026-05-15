package com.gridstore.huevista.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RecommendationResponse {
    private String projectId;
    private String imageType;
    private List<ColorCombo> combinations;
}
