package com.pauldev.animan.repository;

import com.pauldev.animan.model.WatchProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WatchProgressRepository extends JpaRepository<WatchProgress, Long> {
    List<WatchProgress> findByAnimeUrl(String animeUrl);
    Optional<WatchProgress> findByAnimeUrlAndEpisodeNumberAndVersion(String animeUrl, int episodeNumber, String version);
    List<WatchProgress> findByAnimeUrlAndDownloadedTrue(String animeUrl);
    List<WatchProgress> findByAnimeUrlAndWatchedTrue(String animeUrl);
}
