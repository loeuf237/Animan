package com.pauldev.animan.service;

import com.pauldev.animan.model.*;
import com.pauldev.animan.repository.FavoriteAnimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service Watchdog : vérifie périodiquement les nouveaux épisodes pour les animés
 * en favoris avec autoDownload activé, et les télécharge automatiquement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchdogService {

    private final FavoriteAnimeRepository favoriteRepo;
    private final ScrapingService scrapingService;
    private final VoirAnimeScrapingService voirAnimeService;
    private final DownloadManagerService downloadManager;
    private final LibraryService libraryService;

    /**
     * Vérification toutes les heures (configurable via animan.watchdog-interval-ms).
     */
    @Scheduled(fixedDelayString = "${animan.watchdog-interval-ms:3600000}")
    public void checkNewEpisodes() {
        List<FavoriteAnime> watchList = favoriteRepo.findByAutoDownloadTrue();
        if (watchList.isEmpty()) return;

        log.info("[Watchdog] Vérification de {} animé(s) en liste d'écoute...", watchList.size());

        for (FavoriteAnime fav : watchList) {
            try {
                checkAnime(fav);
            } catch (Exception e) {
                log.error("[Watchdog] Erreur pour '{}': {}", fav.getTitle(), e.getMessage());
            }
        }
    }

    private void checkAnime(FavoriteAnime fav) {
        log.info("[Watchdog] Vérification de '{}'...", fav.getTitle());

        AnimeDetail detail;
        if ("voir-anime".equals(fav.getSource())) {
            detail = voirAnimeService.getAnimeDetail(fav.getUrl());
        } else {
            detail = scrapingService.getAnimeDetail(fav.getUrl());
        }

        if (detail == null || detail.getEpisodes().isEmpty()) {
            log.warn("[Watchdog] Impossible de charger les épisodes de '{}'", fav.getTitle());
            return;
        }

        int currentMax = detail.getEpisodes().stream()
                .mapToInt(Episode::getNumber).max().orElse(0);
        int lastKnown = fav.getLastKnownEpisode();

        if (currentMax > lastKnown) {
            int newEpisodesCount = currentMax - lastKnown;
            log.info("[Watchdog] {} nouveau(x) épisode(s) trouvé(s) pour '{}'!",
                     newEpisodesCount, fav.getTitle());

            // Télécharger les nouveaux épisodes
            for (Episode ep : detail.getEpisodes()) {
                if (ep.getNumber() > lastKnown) {
                    String url = ep.getDownloadPageUrl() != null && !ep.getDownloadPageUrl().isBlank()
                                 ? ep.getDownloadPageUrl() : ep.getPlayerUrl();
                    if (url == null || url.isBlank()) continue;

                    String fileName = fav.getTitle() + " - Episode " + ep.getNumber() + ".mp4";
                    log.info("[Watchdog] Auto-download: {} Ep{}", fav.getTitle(), ep.getNumber());
                    downloadManager.startDownload(
                        fav.getTitle(), ep.getNumber(), url, fileName,
                        ep.getSubtitleUrl(), null, 0, false);
                }
            }

            // Mettre à jour le dernier épisode connu
            libraryService.updateLastKnownEpisode(fav.getUrl(), currentMax);
        } else {
            log.info("[Watchdog] Pas de nouveauté pour '{}' (dernier connu: Ep{})",
                     fav.getTitle(), lastKnown);
            libraryService.updateLastKnownEpisode(fav.getUrl(), currentMax);
        }
    }
}
