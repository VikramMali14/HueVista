package com.gridstore.huevista.project.queue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SegmentationJob {
    private String projectId;
    private String imageUrl;
}
