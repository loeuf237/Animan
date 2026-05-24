package com.pauldev.animan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Détails complets d'un anime (page fullstory).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimeDetail {

    private String title;
    private String originalTitle;
    private String synopsis;
    private String imageUrl;
    private String version;       // VF, VOSTFR, VF+VOSTFR
    private String studio;
    private String url;

    @Builder.Default
    private List<String> genres = new ArrayList<>();

    @Builder.Default
    private List<String> directors = new ArrayList<>();

    @Builder.Default
    private List<String> actors = new ArrayList<>();

    @Builder.Default
    private List<Episode> episodes = new ArrayList<>();
}
