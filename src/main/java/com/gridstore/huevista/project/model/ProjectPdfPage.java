package com.gridstore.huevista.project.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One page of one colour board — a single coloured version of the room, as it was handed
 * to the customer.
 *
 * This exists because until now the server never learned what was in a PDF. The board was
 * built entirely in the browser and the only call home was a bare "charge me for one
 * download" with no body: no project, no shades, nothing. That was fine while a board was
 * just a receipt, but the whole closing flow is built on what the customer actually took
 * away — the eight combos they chose between, the shades that stay visible once the
 * project closes, and the one they pick to render. None of that can be reconstructed
 * afterwards, because a Region only ever holds the colour applied to it RIGHT NOW and is
 * overwritten in place every time the customer tries another shade.
 *
 * Pages are numbered twice over, and both numbers matter: {@link #boardIndex} says which
 * download it came from (1 or 2) and {@link #pageIndex} where it sat inside that
 * document. Together they order the eight combos exactly as the customer saw them.
 *
 * No pixels are stored. The selection page re-renders each combo from the cleaned photo,
 * the region masks and the hexes below — all of which already exist — which stays sharp
 * at any size and costs nothing to keep.
 */
@Entity
@Table(name = "project_pdf_pages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectPdfPage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Which colour board this page came from, counting from 1. */
    @Column(nullable = false)
    private int boardIndex;

    /** Where the page sat inside that board, counting from 0. */
    @Column(nullable = false)
    private int pageIndex;

    /** What the studio called this combination, when it had a name to give. */
    @Column(length = 160)
    private String title;

    @OneToMany(mappedBy = "page", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<ProjectPdfPageShade> shades = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;
}
