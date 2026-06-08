package com.pauldev.animan.repository;

import com.pauldev.animan.model.FavoriteAnime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteAnimeRepository extends JpaRepository<FavoriteAnime, Long> {
    Optional<FavoriteAnime> findByUrl(String url);
    boolean existsByUrl(String url);
    List<FavoriteAnime> findByAutoDownloadTrue();
}
