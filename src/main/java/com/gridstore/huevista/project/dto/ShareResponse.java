package com.gridstore.huevista.project.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShareResponse {
    private String shareUrl;
    private String shareToken;
    private LocalDateTime expiresAt;
}
