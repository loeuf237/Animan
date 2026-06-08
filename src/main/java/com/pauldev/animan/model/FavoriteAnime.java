package com.pauldev.animan.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Anime en favoris (Ma Liste).
 * Utilisé aussi par le Watchdog pour l'auto-download des nouveaux épisodes.
 */
@Entity
@Table(name = "favorites")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteAnime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String url;

    @Column(nullable = false)
    private String title;

    private String imageUrl;
    private String source; // "french-manga" ou "voir-anime"

    /** Dernier épisode connu (pour détecter les nouveautés via Watchdog). */
    @Builder.Default
    private int lastKnownEpisode = 0;

    /** Auto-télécharger les nouveaux épisodes automatiquement. */
    @Builder.Default
    private boolean autoDownload = false;

    @Builder.Default
    private LocalDateTime addedAt = LocalDateTime.now();

    private LocalDateTime lastCheckedAt;
}
