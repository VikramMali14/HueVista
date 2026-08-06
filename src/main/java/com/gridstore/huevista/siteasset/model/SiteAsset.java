package com.gridstore.huevista.siteasset.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One image currently occupying one slot of the public marketing site.
 *
 * The slot IS the identity — there is one row per position in the design, not
 * one per upload — so replacing an image is an update in place and the file it
 * displaced is deleted with it. No row for a slot means the front end draws its
 * built-in default, which is a perfectly good state and the one a fresh install
 * is in.
 */
@Entity
@Table(name = "site_assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteAsset {

    /** Dotted slot id, e.g. "home.compare.before". The front end owns the registry
     *  of which slots exist; the service refuses any id it is not told about. */
    @Id
    @Column(length = 120, nullable = false)
    private String slot;

    @Column(nullable = false, length = 512)
    private String storageKey;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;

    /** Read off the image at upload, so the admin page can flag a picture that is
     *  nothing like the shape its slot is drawn at. Null if it could not be read —
     *  an unreadable header is not a reason to refuse a valid upload. */
    private Integer width;
    private Integer height;

    /** Only so the admin page can name the file the admin actually chose. */
    @Column(length = 255)
    private String originalFilename;

    @Column(length = 255)
    private String updatedByUserId;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
