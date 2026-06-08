package com.pauldev.animan.service;

import com.pauldev.animan.model.*;
import com.pauldev.animan.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Gestion de la bibliothèque: favoris, progression, historique.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryService {

    private final FavoriteAnimeRepository favoriteRepo;
    private final WatchProgressRepository progressRepo;
    private final DownloadTaskRepository taskRepo;

    // =========================================================================
    // FAVORIS
    // =========================================================================

    public FavoriteAnime addFavorite(String url, String title, String imageUrl, String source) {
        if (favoriteRepo.existsByUrl(url)) {
            return favoriteRepo.findByUrl(url).orElseThrow();
        }
        FavoriteAnime fav = FavoriteAnime.builder()
                .url(url).title(title).imageUrl(imageUrl).source(source)
                .addedAt(LocalDateTime.now())
                .build();
        log.info("Ajout aux favoris: {}", title);
        return favoriteRepo.save(fav);
    }

    public void removeFavorite(String url) {
        favoriteRepo.findByUrl(url).ifPresent(f -> {
            favoriteRepo.delete(f);
            log.info("Retiré des favoris: {}", f.getTitle());
        });
    }

    public boolean isFavorite(String url) {
        return favoriteRepo.existsByUrl(url);
    }

    public List<FavoriteAnime> getAllFavorites() {
        return favoriteRepo.findAll();
    }

    @Transactional
    public FavoriteAnime updateAutoDownload(String url, boolean autoDownload) {
        FavoriteAnime fav = favoriteRepo.findByUrl(url)
                .orElseThrow(() -> new IllegalArgumentException("Favori introuvable: " + url));
        fav.setAutoDownload(autoDownload);
        return favoriteRepo.save(fav);
    }

    @Transactional
    public void updateLastKnownEpisode(String url, int episodeCount) {
        favoriteRepo.findByUrl(url).ifPresent(fav -> {
            fav.setLastKnownEpisode(episodeCount);
            fav.setLastCheckedAt(LocalDateTime.now());
            favoriteRepo.save(fav);
        });
    }

    // =========================================================================
    // PROGRESSION
    // =========================================================================

    @Transactional
    public WatchProgress markAsDownloaded(String animeUrl, String animeName,
                                          int episodeNumber, String version) {
        WatchProgress progress = progressRepo
                .findByAnimeUrlAndEpisodeNumberAndVersion(animeUrl, episodeNumber, version)
                .orElseGet(() -> WatchProgress.builder()
                        .animeUrl(animeUrl)
                        .animeName(animeName)
                        .episodeNumber(episodeNumber)
                        .version(version != null ? version : "")
                        .build());
        progress.setDownloaded(true);
        progress.setDownloadedAt(LocalDateTime.now());
        return progressRepo.save(progress);
    }

    @Transactional
    public WatchProgress markAsWatched(String animeUrl, String animeName,
                                       int episodeNumber, String version) {
        WatchProgress progress = progressRepo
                .findByAnimeUrlAndEpisodeNumberAndVersion(animeUrl, episodeNumber, version)
                .orElseGet(() -> WatchProgress.builder()
                        .animeUrl(animeUrl)
                        .animeName(animeName)
                        .episodeNumber(episodeNumber)
                        .version(version != null ? version : "")
                        .build());
        progress.setWatched(true);
        progress.setWatchedAt(LocalDateTime.now());
        return progressRepo.save(progress);
    }

    public List<WatchProgress> getProgressForAnime(String animeUrl) {
        return progressRepo.findByAnimeUrl(animeUrl);
    }

    /** Retourne un set d'épisodes téléchargés: "epNum-version" */
    public Set<String> getDownloadedEpisodesSet(String animeUrl) {
        Set<String> set = new HashSet<>();
        for (WatchProgress p : progressRepo.findByAnimeUrlAndDownloadedTrue(animeUrl)) {
            set.add(p.getEpisodeNumber() + "-" + p.getVersion());
        }
        return set;
    }

    public Set<String> getWatchedEpisodesSet(String animeUrl) {
        Set<String> set = new HashSet<>();
        for (WatchProgress p : progressRepo.findByAnimeUrlAndWatchedTrue(animeUrl)) {
            set.add(p.getEpisodeNumber() + "-" + p.getVersion());
        }
        return set;
    }

    // =========================================================================
    // HISTORIQUE (persisté en H2)
    // =========================================================================

    public void saveTask(DownloadTask task) {
        try {
            taskRepo.save(DownloadTaskEntity.fromTask(task));
        } catch (Exception e) {
            log.warn("Impossible de sauvegarder la tâche {}: {}", task.getId(), e.getMessage());
        }
    }

    public List<DownloadTaskEntity> getHistory() {
        return taskRepo.findAllByOrderByCreatedAtDesc();
    }

    public List<DownloadTaskEntity> getHistoryByAnime(String animeName) {
        return taskRepo.findByAnimeName(animeName);
    }

    public Optional<DownloadTaskEntity> getTaskEntity(String id) {
        return taskRepo.findById(id);
    }

    public List<DownloadTaskEntity> getUnfinishedTasks() {
        return taskRepo.findByStatusIn(Arrays.asList(
            DownloadTask.DownloadStatus.PENDING,
            DownloadTask.DownloadStatus.SCHEDULED,
            DownloadTask.DownloadStatus.DOWNLOADING,
            DownloadTask.DownloadStatus.RETRYING,
            DownloadTask.DownloadStatus.PAUSED
        ));
    }
}
