package com.gridstore.huevista.library.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What "start a copy" hands back: enough to send the caller straight to the
 * studio. The project itself is then read through the ordinary project API, so
 * a free project is indistinguishable from any other once it exists.
 */
@Data
@Builder
public class StartedProjectResponse {
    private String projectId;
    private String name;
    private String status;
    private int regionCount;
    private String templateId;
    private String templateTitle;
}
