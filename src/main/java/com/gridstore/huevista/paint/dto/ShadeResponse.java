package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.Shade;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

// Serializable so the @Cacheable shade endpoints can be stored in Redis
// (the cache uses JDK serialization for values).
@Data
@Builder
public class ShadeResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String brandName;
    private String brandSlug;
    private String shadeCode;
    private String name;
    private String hexCode;
    private String shadeFamily;
    private String featureTag;
    private Integer popularity;
    private String pageUrl;
    private String colorTemperature;
    private String tonality;
    private List<String> suitableRooms;

    // Calculated
    private BigDecimal lrv;
    private Integer rgbR;
    private Integer rgbG;
    private Integer rgbB;

    // AI-enriched
    private List<String> styleTags;
    private List<String> moodDescriptors;
    private List<String> finishRecommendations;
    private String aiDescription;

    public static ShadeResponse from(Shade shade) {
        return ShadeResponse.builder()
                .id(shade.getId())
                .brandName(shade.getBrand().getName())
                .brandSlug(shade.getBrand().getSlug())
                .shadeCode(shade.getShadeCode())
                .name(shade.getName())
                .hexCode(shade.getHexCode())
                .shadeFamily(shade.getShadeFamily())
                .featureTag(shade.getFeatureTag())
                .popularity(shade.getPopularity())
                .pageUrl(shade.getPageUrl())
                .colorTemperature(shade.getColorTemperature())
                .tonality(shade.getTonality())
                .suitableRooms(shade.getSuitableRooms())
                .lrv(shade.getLrv())
                .rgbR(shade.getRgbR())
                .rgbG(shade.getRgbG())
                .rgbB(shade.getRgbB())
                .styleTags(shade.getStyleTags())
                .moodDescriptors(shade.getMoodDescriptors())
                .finishRecommendations(shade.getFinishRecommendations())
                .aiDescription(shade.getAiDescription())
                .build();
    }
}
