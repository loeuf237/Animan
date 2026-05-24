package com.pauldev.animan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Épisode d'un anime avec lien de streaming/téléchargement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Episode {

    private int number;
    private String title;
    private String playerUrl;         // URL du lecteur vidéo (iframe src)
    private String downloadPageUrl;   // URL de la page de téléchargement (vidzy.live/d/...)
    private String directDownloadUrl; // URL directe du fichier vidéo (.mp4)
    private String subtitleUrl;       // URL des sous-titres (si disponible)
    private String version;           // VF / VOSTFR
    private String synopsis;          // Synopsis de l'épisode (optionnel)
}
