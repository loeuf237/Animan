package com.pauldev.animan.controller;

import com.pauldev.animan.model.*;
import com.pauldev.animan.repository.DownloadTaskRepository;
import com.pauldev.animan.service.LibraryService;
import com.pauldev.animan.service.ScrapingService;
import com.pauldev.animan.service.VoirAnimeScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Contrôleur pour la bibliothèque: favoris, historique, progression, voir-anime.to.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class LibraryController {

    private final LibraryService libraryService;
    private final ScrapingService scrapingService;
    private final VoirAnimeScrapingService voirAnimeService;
    private final DownloadTaskRepository taskRepo;

    // =========================================================================
    // Pages
    // =========================================================================

    @GetMapping("/library")
    public String libraryPage(Model model) {
        model.addAttribute("favorites", libraryService.getAllFavorites());
        model.addAttribute("history", libraryService.getHistory());
        return "library";
    }

    @GetMapping("/history")
    public String historyPage(Model model) {
        model.addAttribute("tasks", libraryService.getHistory());
        return "history";
    }

    // =========================================================================
    // Favoris API
    // =========================================================================

    @PostMapping("/api/favorites")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addFavorite(@RequestBody Map<String, String> body) {
        try {
            String url = body.get("url");
            String title = body.getOrDefault("title", "");
            String imageUrl = body.getOrDefault("imageUrl", "");
            String source = body.getOrDefault("source", "french-manga");
            FavoriteAnime fav = libraryService.addFavorite(url, title, imageUrl, source);
            return ResponseEntity.ok(Map.of("success", true, "id", fav.getId(),
                    "message", "Ajouté aux favoris: " + title));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/favorites")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> removeFavorite(@RequestBody Map<String, String> body) {
        try {
            libraryService.removeFavorite(body.get("url"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/favorites/check")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkFavorite(@RequestParam String url) {
        return ResponseEntity.ok(Map.of("isFavorite", libraryService.isFavorite(url)));
    }

    @GetMapping("/api/favorites")
    @ResponseBody
    public List<FavoriteAnime> getFavorites() {
        return libraryService.getAllFavorites();
    }

    @PatchMapping("/api/favorites/auto-download")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleAutoDownload(@RequestBody Map<String, Object> body) {
        try {
            String url = (String) body.get("url");
            boolean auto = Boolean.parseBoolean(String.valueOf(body.get("autoDownload")));
            libraryService.updateAutoDownload(url, auto);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/favorites/all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearAllFavorites() {
        try {
            libraryService.removeAllFavorites();
            return ResponseEntity.ok(Map.of("success", true, "message", "Bibliothèque vidée"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // Progression API
    // =========================================================================

    @PostMapping("/api/progress/watched")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> markWatched(@RequestBody Map<String, String> body) {
        try {
            libraryService.markAsWatched(
                body.get("animeUrl"), body.get("animeName"),
                Integer.parseInt(body.get("episodeNumber")),
                body.getOrDefault("version", "")
            );
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/progress")
    @ResponseBody
    public List<WatchProgress> getProgress(@RequestParam String animeUrl) {
        return libraryService.getProgressForAnime(animeUrl);
    }

    @GetMapping("/api/progress/downloaded")
    @ResponseBody
    public Set<String> getDownloadedSet(@RequestParam String animeUrl) {
        return libraryService.getDownloadedEpisodesSet(animeUrl);
    }

    // =========================================================================
    // Historique API
    // =========================================================================

    @GetMapping("/api/history")
    @ResponseBody
    public List<DownloadTaskEntity> getHistory(@RequestParam(required = false) String animeName) {
        if (animeName != null && !animeName.isBlank())
            return libraryService.getHistoryByAnime(animeName);
        return libraryService.getHistory();
    }

    @DeleteMapping("/api/history/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteHistoryEntry(@PathVariable String id) {
        try {
            taskRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Transactional
    @DeleteMapping("/api/history")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearHistory(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String animeName) {
        try {
            if (animeName != null && !animeName.isBlank()) {
                taskRepo.deleteByAnimeName(animeName);
                return ResponseEntity.ok(Map.of("success", true, "message", "Historique de " + animeName + " supprimé"));
            }
            if (status != null && !status.isBlank()) {
                var statuses = java.util.Arrays.stream(status.split(","))
                    .map(s -> com.pauldev.animan.model.DownloadTask.DownloadStatus.valueOf(s.trim()))
                    .toList();
                taskRepo.deleteByStatusIn(statuses);
                return ResponseEntity.ok(Map.of("success", true, "message", "Entrées supprimées"));
            }
            taskRepo.deleteAll();
            return ResponseEntity.ok(Map.of("success", true, "message", "Historique entièrement vidé"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // voir-anime.to API
    // =========================================================================

    @GetMapping("/api/voiranime/search")
    @ResponseBody
    public List<AnimeResult> searchVoirAnime(@RequestParam("q") String query) {
        return voirAnimeService.searchAnime(query);
    }

    @GetMapping("/api/voiranime/anime")
    @ResponseBody
    public AnimeDetail getVoirAnimeDetail(@RequestParam("url") String url) {
        return voirAnimeService.getAnimeDetail(url);
    }

    @GetMapping("/api/voiranime/download-info")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getVoirAnimeDownloadInfo(@RequestParam String episodeUrl) {
        try {
            Map<String, String> info = voirAnimeService.extractDownloadInfo(episodeUrl);
            return ResponseEntity.ok(new HashMap<>(info));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/voiranime/qualities")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getVideoQualities(@RequestParam String episodeUrl) {
        return ResponseEntity.ok(voirAnimeService.extractVideoQualities(episodeUrl));
    }

    /**
     * Page de détail pour un animé de voir-anime.to.
     */
    @GetMapping("/voiranime")
    public String voirAnimeDetail(@RequestParam("url") String url, Model model) {
        log.info("Détail voir-anime: {}", url);
        AnimeDetail detail = voirAnimeService.getAnimeDetail(url);
        if (detail == null) {
            model.addAttribute("error", "Impossible de charger cet animé depuis voir-anime.to");
            return "index";
        }
        detail.setUrl(url);
        model.addAttribute("anime", detail);
        model.addAttribute("source", "voir-anime");
        return "anime-detail";
    }
}
