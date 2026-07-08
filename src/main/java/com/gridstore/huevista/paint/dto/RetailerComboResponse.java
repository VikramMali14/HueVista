package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.ComboScope;
import com.gridstore.huevista.paint.model.RetailerCombo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetailerComboResponse {

    private String id;
    private String organizationId;
    private String organizationName;
    private String name;
    private ComboScope scope;
    /** Studio role order: main wall, accent wall, trim. */
    private List<ComboShade> shades;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComboShade {
        private String code;
        private String name;
        private String hex;
    }

    public static RetailerComboResponse from(RetailerCombo combo) {
        return RetailerComboResponse.builder()
                .id(combo.getId())
                .organizationId(combo.getOrganization().getId())
                .organizationName(combo.getOrganization().getName())
                .name(combo.getName())
                .scope(combo.getScope())
                .shades(List.of(
                        new ComboShade(combo.getShade1Code(), combo.getShade1Name(), combo.getShade1Hex()),
                        new ComboShade(combo.getShade2Code(), combo.getShade2Name(), combo.getShade2Hex()),
                        new ComboShade(combo.getShade3Code(), combo.getShade3Name(), combo.getShade3Hex())))
                .createdAt(combo.getCreatedAt())
                .build();
    }
}
