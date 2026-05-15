package com.gridstore.huevista.ai.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ColorCombo {
    private String name;
    private String rationale;

    private String primaryHex;
    private MatchedShade primaryShade;

    private String accentHex;
    private MatchedShade accentShade;

    private String trimHex;
    private MatchedShade trimShade;
}
