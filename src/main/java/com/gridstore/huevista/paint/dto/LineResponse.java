package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.PaintLine;
import com.gridstore.huevista.paint.model.ProductCategory;
import com.gridstore.huevista.paint.model.QualityTier;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LineResponse {
    private Long id;
    private String name;
    private ProductCategory category;
    private QualityTier qualityTier;
    private String defaultFinish;

    public static LineResponse from(PaintLine l) {
        return LineResponse.builder()
                .id(l.getId())
                .name(l.getName())
                .category(l.getCategory())
                .qualityTier(l.getQualityTier())
                .defaultFinish(l.getDefaultFinish())
                .build();
    }
}
