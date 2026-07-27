package com.gridstore.huevista.hierarchy.dto;

import com.gridstore.huevista.account.model.AppFeature;
import lombok.Builder;
import lombok.Data;

/**
 * One page a distributor could switch on for a shop, with whether it currently is.
 * The sibling of {@link RetailerBrandOption} — the distributor's page-access editor
 * renders one row per option so a single call fills the whole checklist.
 */
@Data
@Builder
public class RetailerFeatureOption {

    /** The {@link AppFeature} name, e.g. {@code COLOR_FINDER}. */
    private String key;
    private String label;
    /** Frontend route this option gates, e.g. {@code /color-finder}. */
    private String path;
    private String description;
    private boolean assigned;

    public static RetailerFeatureOption of(AppFeature feature, boolean assigned) {
        return RetailerFeatureOption.builder()
                .key(feature.name())
                .label(feature.getLabel())
                .path(feature.getPath())
                .description(feature.getDescription())
                .assigned(assigned)
                .build();
    }
}
