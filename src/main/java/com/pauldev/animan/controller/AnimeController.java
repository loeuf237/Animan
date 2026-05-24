package com.pauldev.animan.controller;

import com.pauldev.animan.model.AnimeDetail;
import com.pauldev.animan.model.AnimeResult;
import com.pauldev.animan.service.ScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Contrôleur web pour la recherche et l'affichage des animés.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AnimeController {

    private final ScrapingService scrapingService;

    /**
     * Page d'accueil - recherche d'animés.
     */
    @GetMapping("/")
    public String home() {
        return "index";
    }

    /**
     * Recherche d'animés et affichage des résultats.
     */
    @GetMapping("/search")
    public String search(@RequestParam("q") String query, Model model) {
        log.info("Recherche: {}", query);
        List<AnimeResult> results = scrapingService.searchAnime(query);
        model.addAttribute("results", results);
        model.addAttribute("query", query);
        return "index";
    }

    /**
     * Recherche AJAX - retourne uniquement le fragment résultats.
     */
    @GetMapping("/api/search")
    @ResponseBody
    public List<AnimeResult> searchApi(@RequestParam("q") String query) {
        return scrapingService.searchAnime(query);
    }

    /**
     * Page de détail d'un anime avec liste des épisodes.
     */
    @GetMapping("/anime")
    public String animeDetail(@RequestParam("url") String url, Model model) {
        log.info("Détail anime: {}", url);
        AnimeDetail detail = scrapingService.getAnimeDetail(url);

        if (detail == null) {
            model.addAttribute("error", "Impossible de charger cet anime. Vérifiez l'URL.");
            return "index";
        }

        model.addAttribute("anime", detail);
        return "anime-detail";
    }
}
