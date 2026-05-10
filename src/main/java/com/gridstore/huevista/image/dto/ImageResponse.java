package com.gridstore.huevista.image.dto;

import com.gridstore.huevista.image.model.ImageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageResponse {
    private String imageId;
    private String imageUrl;
    private String originalFilename;
    private ImageType imageType;
    private long fileSize;
    private LocalDateTime uploadedAt;
}
