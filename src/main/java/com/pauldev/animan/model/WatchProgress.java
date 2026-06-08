package com.pauldev.animan.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Suivi de progression : quels épisodes ont été téléchargés/vus.
 */
@Entity
@Table(name = "watch_progress",
       uniqueConstraints = @UniqueConstraint(columnNames = {"animeUrl", "episodeNumber", "version"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String animeUrl;

    @Column(nullable = false)
    private String animeName;

    private int episodeNumber;
    private String version; // VF / VOSTFR

    @Builder.Default
    private boolean downloaded = false;

    @Builder.Default
    private boolean watched = false;

    private LocalDateTime downloadedAt;
    private LocalDateTime watchedAt;
}
