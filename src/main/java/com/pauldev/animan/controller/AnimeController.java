package com.pauldev.animan.controller;

import com.pauldev.animan.model.AnimeDetail;
import com.pauldev.animan.model.AnimeResult;
import com.pauldev.animan.service.LibraryService;
import com.pauldev.animan.service.ScrapingService;
import com.pauldev.animan.service.VoirAnimeScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AnimeController {

    private final ScrapingService scrapingService;
    private final VoirAnimeScrapingService voirAnimeService;
    private final LibraryService libraryService;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("favorites", libraryService.getAllFavorites());
        model.addAttribute("latestFrenchManga", scrapingService.getLatestReleases());
        model.addAttribute("latestVoirAnime", voirAnimeService.getLatestReleases());
        return "index";
    }

    @GetMapping("/search")
    public String search(@RequestParam("q") String query,
                         @RequestParam(value = "source", defaultValue = "french-manga") String source,
                         Model model) {
        log.info("Recherche [{}]: {}", source, query);
        List<AnimeResult> results;
        if ("voir-anime".equals(source)) {
            results = voirAnimeService.searchAnime(query);
        } else {
            results = scrapingService.searchAnime(query);
            // Fallback vers voir-anime si aucun résultat
            if (results.isEmpty()) {
                log.info("Aucun résultat sur french-manga, fallback vers voir-anime.to...");
                results = voirAnimeService.searchAnime(query);
                if (!results.isEmpty()) source = "voir-anime";
            }
        }
        model.addAttribute("results", results);
        model.addAttribute("query", query);
        model.addAttribute("source", source);
        model.addAttribute("favorites", libraryService.getAllFavorites());
        return "index";
    }

    @GetMapping("/api/search")
    @ResponseBody
    public List<AnimeResult> searchApi(@RequestParam("q") String query,
                                        @RequestParam(value = "source", defaultValue = "french-manga") String source) {
        if ("voir-anime".equals(source)) return voirAnimeService.searchAnime(query);
        List<AnimeResult> results = scrapingService.searchAnime(query);
        if (results.isEmpty()) results = voirAnimeService.searchAnime(query);
        return results;
    }

    @GetMapping("/anime")
    public String animeDetail(@RequestParam("url") String url,
                               @RequestParam(value = "source", defaultValue = "french-manga") String source,
                               Model model) {
        log.info("Détail anime [{}]: {}", source, url);
        AnimeDetail detail;
        if ("voir-anime".equals(source)) {
            detail = voirAnimeService.getAnimeDetail(url);
        } else {
            detail = scrapingService.getAnimeDetail(url);
        }

        if (detail == null) {
            model.addAttribute("error", "Impossible de charger cet anime.");
            return "index";
        }

        // Progression téléchargements
        Set<String> downloadedEpisodes = libraryService.getDownloadedEpisodesSet(url);
        Set<String> watchedEpisodes = libraryService.getWatchedEpisodesSet(url);
        boolean isFavorite = libraryService.isFavorite(url);

        model.addAttribute("anime", detail);
        model.addAttribute("source", source);
        model.addAttribute("isFavorite", isFavorite);
        model.addAttribute("downloadedEpisodes", downloadedEpisodes);
        model.addAttribute("watchedEpisodes", watchedEpisodes);
        return "anime-detail";
    }
}
