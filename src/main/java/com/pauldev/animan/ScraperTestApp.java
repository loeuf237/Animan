package com.pauldev.animan;

import com.pauldev.animan.model.AnimeResult;
import com.pauldev.animan.model.AnimeDetail;
import com.pauldev.animan.service.ScrapingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

import java.util.List;

@SpringBootApplication
@Profile("test-scraper")
public class ScraperTestApp implements CommandLineRunner {

    @Autowired
    private ScrapingService scrapingService;

    public static void main(String[] args) {
        SpringApplication.run(ScraperTestApp.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("--- TESTING SCRAPER ---");
        
        System.out.println("1. Testing search for 'Naruto'");
        List<AnimeResult> results = scrapingService.searchAnime("Naruto");
        System.out.println("Found " + results.size() + " results");
        for (AnimeResult r : results) {
            System.out.println("- " + r.getTitle() + " -> " + r.getUrl());
        }
        
        if (!results.isEmpty()) {
            String url = results.get(0).getUrl();
            System.out.println("\n2. Testing detail page for " + url);
            AnimeDetail detail = scrapingService.getAnimeDetail(url);
            System.out.println("Title: " + detail.getTitle());
            System.out.println("Episodes: " + detail.getEpisodes().size());
            if (!detail.getEpisodes().isEmpty()) {
                System.out.println("First Episode URL: " + detail.getEpisodes().get(0).getPlayerUrl() + " | Download: " + detail.getEpisodes().get(0).getDownloadPageUrl());
            }
        } else {
            System.out.println("\n2. Testing a direct URL instead (since search failed)");
            AnimeDetail detail = scrapingService.getAnimeDetail("https://w16.french-manga.net/index.php?newsid=1498849");
            System.out.println("Title: " + detail.getTitle());
            System.out.println("Episodes: " + (detail != null && detail.getEpisodes() != null ? detail.getEpisodes().size() : "null"));
        }
        
        System.out.println("--- TEST COMPLETE ---");
        System.exit(0);
    }
}
