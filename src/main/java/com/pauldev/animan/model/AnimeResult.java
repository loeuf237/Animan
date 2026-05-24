package com.pauldev.animan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Résultat de recherche d'anime.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimeResult {

    private String id;
    private String title;
    private String imageUrl;
    private String url;
    private String version;   // VF, VOSTFR, VF+VOSTFR
    private String type;      // Anime, Film
    private String episodeInfo; // ex: "5 / 11", "7 / 14"
}
