package com.gridstore.huevista.paint.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gridstore.huevista.paint.model.Shade;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lightweight projection of {@link Shade} for the catalogue LIST endpoints
 * ({@code GET /api/shades} and {@code GET /api/shades/{brand}}).
 *
 * <p>The full multi-brand catalogue is ~9.5k shades. The heavy AI-enriched prose
 * fields ({@code aiDescription}, {@code styleTags}, {@code moodDescriptors}), plus
 * {@code suitableRooms} and the long {@code pageUrl}, are only meaningful on the
 * single-shade detail endpoint — yet shipping them for every row pushed the list
 * response past 4 MB. Combined with the absence of {@code @JsonInclude(NON_NULL)}
 * (the bulk brands leave those fields null), roughly half of every row was
 * {@code ":null"} noise. Next.js refuses to cache any fetch body over 2 MB, so the
 * frontend re-fetched the whole catalogue on every render.
 *
 * <p>This DTO carries only the fields the catalogue grid + filters render and omits
 * nulls, keeping the list response well under that 2 MB ceiling. Callers needing the
 * AI-enriched prose use {@code GET /api/shades/{brand}/{code}} (still {@link ShadeResponse}).
 *
 * <p>Serializable and returned directly (not wrapped in {@code ResponseEntity}) so the
 * {@code @Cacheable} "shades" cache can JDK-serialize it into Redis.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShadeSummaryResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String brandName;
    private String brandSlug;
    private String shadeCode;
    private String name;
    private String hexCode;
    private String shadeFamily;
    private String featureTag;
    private Integer popularity;
    private String colorTemperature;
    private String tonality;

    private BigDecimal lrv;
    private Integer rgbR;
    private Integer rgbG;
    private Integer rgbB;

    private List<String> finishRecommendations;

    public static ShadeSummaryResponse from(Shade shade) {
        return ShadeSummaryResponse.builder()
                .brandName(shade.getBrand().getName())
                .brandSlug(shade.getBrand().getSlug())
                .shadeCode(shade.getShadeCode())
                .name(shade.getName())
                .hexCode(shade.getHexCode())
                .shadeFamily(shade.getShadeFamily())
                .featureTag(shade.getFeatureTag())
                .popularity(shade.getPopularity())
                .colorTemperature(shade.getColorTemperature())
                .tonality(shade.getTonality())
                .lrv(shade.getLrv())
                .rgbR(shade.getRgbR())
                .rgbG(shade.getRgbG())
                .rgbB(shade.getRgbB())
                .finishRecommendations(shade.getFinishRecommendations())
                .build();
    }
}
