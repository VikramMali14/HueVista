package com.gridstore.huevista.project.dto;

import lombok.Data;

@Data
public class RegionColorUpdate {
    private Long regionId;
    private String shadeCode; // null to clear
    private String hexCode;   // null to clear
}
