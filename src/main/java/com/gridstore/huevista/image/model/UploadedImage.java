package com.gridstore.huevista.image.model;

import com.gridstore.huevista.account.model.CustomerAccessCode;
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

    // Owner is EITHER a registered user OR — for an anonymous guest who redeemed a
    // shop access code — the access code (user stays null). Exactly one is set.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_code_id")
    private CustomerAccessCode accessCode;

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

    // What a closer look at this photo found — see ClaudeVisionService.analyseStored.
    // All four are null on every upload: the upload path deliberately asks only the
    // scene question, so these are filled in later by a run that explicitly asked for
    // the analysis (the ADMIN analysePhoto knob). They describe the PHOTO rather than
    // the run, so they live here and not on Project: a second run of the same image can
    // reuse the answer instead of paying for it again.
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private HouseType houseType;

    // The colour the walls are RIGHT NOW, as they appear under this photo's own light —
    // "#rrggbb" plus the model's everyday name for it. Null whenever no colour could be
    // read honestly: an unpainted wall, deep shadow, or a wall too small in the frame.
    // Shown next to catalogue shades, so a wrong value here is worse than no value.
    @Column(length = 7)
    private String detectedWallHex;

    @Column(length = 64)
    private String detectedWallColour;

    // The trim colour, when it is clearly different from the walls. Null otherwise.
    @Column(length = 7)
    private String detectedTrimHex;

    @CreationTimestamp
    private LocalDateTime uploadedAt;
}
