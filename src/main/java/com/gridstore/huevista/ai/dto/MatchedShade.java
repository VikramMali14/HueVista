package com.gridstore.huevista.ai.dto;

import com.gridstore.huevista.paint.model.Shade;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MatchedShade {
    private Long id;
    private String shadeCode;
    private String name;
    private String hexCode;
    private String brand;
    private String shadeFamily;
    private String aiDescription;
    private double deltaE;

    public static MatchedShade from(Shade shade, double deltaE) {
        return MatchedShade.builder()
                .id(shade.getId())
                .shadeCode(shade.getShadeCode())
                .name(shade.getName())
                .hexCode(shade.getHexCode())
                .brand(shade.getBrand().getName())
                .shadeFamily(shade.getShadeFamily())
                .aiDescription(shade.getAiDescription())
                .deltaE(Math.round(deltaE * 100.0) / 100.0)
                .build();
    }
}
