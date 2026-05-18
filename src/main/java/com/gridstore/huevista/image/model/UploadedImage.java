package com.gridstore.huevista.image.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "uploaded_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadedImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String originalFilename;

    // key/path in local or cloud storage
    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;

    // Pixel dimensions of the stored image. Nullable for backfill — populated
    // lazily the first time a feature needs them (e.g. click-to-segment).
    private Integer width;
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImageType imageType;

    @CreationTimestamp
    private LocalDateTime uploadedAt;
}
