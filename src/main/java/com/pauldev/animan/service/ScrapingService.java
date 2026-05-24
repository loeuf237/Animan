package com.pauldev.animan.service;

import com.pauldev.animan.model.AnimeDetail;
import com.pauldev.animan.model.AnimeResult;
import com.pauldev.animan.model.Episode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Service de scraping pour french-manga.net (DLE CMS).
 * Gère la recherche, le parsing des pages anime, et l'extraction des liens de téléchargement.
 */
@Slf4j
@Service
public class ScrapingService {

    @Value("${animan.base-url}")
    private String baseUrl;

    @Value("${animan.user-agent}")
    private String userAgent;

    @Value("${animan.connection-timeout}")
    private int connectionTimeout;

    @Value("${animan.read-timeout}")
    private int readTimeout;

    private static final Pattern NEWSID_PATTERN = Pattern.compile("newsid=(\\d+)");

    // Pattern pour extraire les URLs d'épisodes depuis les tableaux JavaScript dans la page
    // Le site utilise des tableaux JS comme: var defined_vostfr = ["url1","url2",...];
    private static final Pattern JS_ARRAY_PATTERN =
            Pattern.compile("var\\s+(defined_\\w+)\\s*=\\s*\\[([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    // Pattern pour extraire les URLs vidzy depuis les iframes
    private static final Pattern VIDZY_URL_PATTERN =
            Pattern.compile("(https?://(?:vidzy\\.live|luluvdo\\.com)[^\"'\\s]+)", Pattern.CASE_INSENSITIVE);

    // Pattern pour les URLs de téléchargement direct vidzy
    private static final Pattern VIDZY_DOWNLOAD_PATTERN =
            Pattern.compile("(https?://vidzy\\.live/d/[^\"'\\s]+)", Pattern.CASE_INSENSITIVE);

    // Pattern pour extraire l'URL du fichier vidéo depuis la page vidzy
    private static final Pattern VIDEO_FILE_PATTERN =
            Pattern.compile("(?:file|src|source|url|video_url|sources)\\s*[:=]\\s*['\"]([^'\"]+\\.(?:mp4|mkv|avi)(?:\\?[^'\"]*)?)['\"]",
                    Pattern.CASE_INSENSITIVE);

    // Pattern pour les sous-titres
    private static final Pattern SUBTITLE_PATTERN =
            Pattern.compile("(?:subtitle|track|sub|caption|captions|vtt|srt)\\s*[:=]\\s*['\"]([^'\"]+\\.(?:vtt|srt|ass)(?:\\?[^'\"]*)?)['\"]",
                    Pattern.CASE_INSENSITIVE);

    // Pattern alternatif pour les sous-titres dans les tracks HTML5
    private static final Pattern TRACK_PATTERN =
            Pattern.compile("<track[^>]+src=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    /**
     * Recherche d'animés via le moteur DLE (POST).
     */
    public List<AnimeResult> searchAnime(String query) {
        log.info("Recherche anime: '{}'", query);
        List<AnimeResult> results = new ArrayList<>();

        try {
            // AJAX search via POST
            Connection.Response response = Jsoup.connect(baseUrl + "/engine/ajax/search.php")
                    .userAgent(userAgent)
                    .timeout(connectionTimeout)
                    .method(Connection.Method.POST)
                    .data("query", query)
                    .data("page", "1")
                    .referrer(baseUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .followRedirects(true)
                    .execute();

            Document doc = response.parse();
            results = parseSearchResults(doc);

            // Si pas de résultats via POST, essayer le listing filtré
            if (results.isEmpty()) {
                log.info("Recherche POST sans résultats, tentative via listing...");
                results = searchViaListing(query);
            }

        } catch (IOException e) {
            log.error("Erreur lors de la recherche: {}", e.getMessage());
            try {
                results = searchViaListing(query);
            } catch (IOException ex) {
                log.error("Erreur fallback listing: {}", ex.getMessage());
            }
        }

        log.info("Trouvé {} résultat(s) pour '{}'", results.size(), query);
        return results;
    }

    /**
     * Parse les résultats de recherche depuis le HTML DLE.
     */
    private List<AnimeResult> parseSearchResults(Document doc) {
        List<AnimeResult> results = new ArrayList<>();

        // Chercher les différents sélecteurs possibles pour les cartes d'anime
        Elements articles = doc.select(".short, .mov, .shortstory, .short-story, article, .anime-card, .movie-item, .story-item, .search-item");

        if (articles.isEmpty()) {
            // Dernier recours: chercher les conteneurs directs des liens newsid
            Elements links = doc.select("a[href*=newsid]");
            articles = new Elements();
            for (Element link : links) {
                if (link.parent() != null && link.parent().parent() != null) {
                    articles.add(link.parent().parent());
                }
            }
        }

        for (Element article : articles) {
            try {
                AnimeResult result = parseAnimeCard(article);
                if (result != null && result.getTitle() != null && !result.getTitle().isBlank()) {
                    boolean isDuplicate = results.stream()
                            .anyMatch(r -> r.getUrl() != null && r.getUrl().equals(result.getUrl()));
                    if (!isDuplicate) {
                        results.add(result);
                    }
                }
            } catch (Exception e) {
                log.debug("Erreur parsing carte anime: {}", e.getMessage());
            }
        }

        return results;
    }

    /**
     * Parse une carte anime individuelle.
     */
    private AnimeResult parseAnimeCard(Element card) {
        String url = "";
        String title = "";
        String imageUrl = "";
        String version = "";
        String type = "Anime";
        String episodeInfo = "";
        String id = "";

        if (card.hasClass("search-item") || card.hasAttr("onclick")) {
            String onclick = card.attr("onclick");
            Pattern p = Pattern.compile("location\\.href\\s*=\\s*['\"]([^'\"]+)['\"]");
            Matcher m = p.matcher(onclick);
            if (m.find()) {
                url = m.group(1);
                if (url.startsWith("/")) {
                    url = baseUrl + url;
                }
            } else {
                return null;
            }

            Element titleEl = card.selectFirst(".search-title");
            if (titleEl != null) {
                title = titleEl.text().trim();
            }

            Element img = card.selectFirst(".search-poster img");
            if (img != null) {
                imageUrl = img.absUrl("src");
                if (imageUrl.isBlank()) {
                    imageUrl = img.attr("src");
                    if (imageUrl.startsWith("/")) {
                        imageUrl = baseUrl + imageUrl;
                    }
                }
            }
        } else {
            // Chercher le lien principal (newsid ou .html)
            Element link = card.selectFirst("a[href*=newsid]");
            if (link == null) {
                link = card.selectFirst("a[href$=.html]:not([href*=xfsearch]):not([href*=do=search])");
            }
            if (link == null) return null;

            url = link.absUrl("href");
            if (url.isBlank() || url.contains("xfsearch") || url.contains("do=search")) return null;

            // Titre - chercher dans h2, h3, ou le titre du lien
            Element titleEl = card.selectFirst("h2, h3, h4, .title, .movie-title, .short-title");
            if (titleEl != null) {
                title = titleEl.text().trim();
            } else {
                title = link.attr("title").isBlank() ? link.text().trim() : link.attr("title").trim();
            }

            // Image
            Element img = card.selectFirst("img[src]");
            if (img != null) {
                imageUrl = img.absUrl("src");
                if (imageUrl.isBlank()) {
                    imageUrl = img.absUrl("data-src");
                }
            }

            // Version (VF/VOSTFR)
            Element versionEl = card.selectFirst("a[href*=version-serie], .version, .quality");
            if (versionEl != null) {
                version = versionEl.text().trim();
            }

            // Type (Anime/Film)
            Element typeEl = card.selectFirst("a[href*='xf=Film'], a[href*='xf=Anime']");
            if (typeEl != null) {
                type = typeEl.text().trim();
            }

            // Info épisodes (ex: "5 / 11")
            Element epInfoEl = card.selectFirst(".ep-count, .episode-count, .ep-status");
            if (epInfoEl != null) {
                episodeInfo = epInfoEl.text().trim();
            }
        }

        if (title.isBlank() || title.length() < 2) return null;

        // ID
        Matcher m = NEWSID_PATTERN.matcher(url);
        if (m.find()) {
            id = m.group(1);
        } else {
            // Tentative d'extraction par pattern de type: /1498849-ichijyoma
            Pattern pId = Pattern.compile("/(\\d+)-");
            Matcher mId = pId.matcher(url);
            if (mId.find()) {
                id = mId.group(1);
            } else {
                id = UUID.randomUUID().toString().substring(0, 8);
            }
        }

        return AnimeResult.builder()
                .id(id)
                .title(title)
                .imageUrl(imageUrl)
                .url(url)
                .version(version)
                .type(type)
                .episodeInfo(episodeInfo)
                .build();
    }


    /**
     * Recherche fallback via le listing paginé.
     */
    private List<AnimeResult> searchViaListing(String query) throws IOException {
        List<AnimeResult> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (int page = 1; page <= 5; page++) {
            String url = baseUrl + "/manga-streaming-1/";
            if (page > 1) {
                url = baseUrl + "/index.php?cstart=" + page + "&do=cat&category=manga-streaming-1";
            }

            Document doc = fetchDocument(url);
            List<AnimeResult> pageResults = parseSearchResults(doc);

            for (AnimeResult result : pageResults) {
                if (result.getTitle().toLowerCase().contains(lowerQuery)) {
                    results.add(result);
                }
            }

            if (results.size() >= 20) break;
        }

        return results;
    }

    /**
     * Récupère les détails complets d'un anime (page fullstory).
     */
    public AnimeDetail getAnimeDetail(String animeUrl) {
        log.info("Chargement détails anime: {}", animeUrl);

        try {
            Document doc = fetchDocument(animeUrl);

            AnimeDetail detail = new AnimeDetail();
            detail.setUrl(animeUrl);

            // === TITRE ===
            Element titleEl = doc.selectFirst("h1, .full-title, .movie-title, #news-title");
            if (titleEl != null) {
                detail.setTitle(titleEl.text().trim());
            } else {
                detail.setTitle(doc.title().replaceAll("\\|.*", "").trim());
            }

            // === IMAGE / POSTER ===
            Element posterImg = doc.selectFirst(".full-story img[src], .fstory img[src], .movie-poster img[src], .poster img[src], .short-img img[src]");
            if (posterImg != null) {
                detail.setImageUrl(posterImg.absUrl("src"));
            }

            // === SYNOPSIS ===
            Element synopsisEl = doc.selectFirst(".full-story .full-text, .fstory .ftext, .movie-description, .story-text, .fdesc");
            if (synopsisEl != null) {
                detail.setSynopsis(synopsisEl.text().trim());
            } else {
                Elements paragraphs = doc.select(".full-story p, .fstory p, #dle-content p");
                StringBuilder sb = new StringBuilder();
                for (Element p : paragraphs) {
                    String text = p.text().trim();
                    if (text.length() > 50 && !text.contains("Genre") && !text.contains("Studio")) {
                        sb.append(text).append(" ");
                    }
                }
                if (!sb.isEmpty()) {
                    detail.setSynopsis(sb.toString().trim());
                }
            }

            // === METADATA ===
            parseMetadata(doc, detail);

            // === ID / NEWSID ===
            String newsid = "";
            Matcher m = NEWSID_PATTERN.matcher(animeUrl);
            if (m.find()) {
                newsid = m.group(1);
            } else {
                Pattern pId = Pattern.compile("/(\\d+)-");
                Matcher mId = pId.matcher(animeUrl);
                if (mId.find()) {
                    newsid = mId.group(1);
                }
            }

            if (newsid.isEmpty()) {
                Element canonical = doc.selectFirst("link[rel=canonical]");
                if (canonical != null) {
                    String canonicalUrl = canonical.attr("href");
                    Matcher mCanon = NEWSID_PATTERN.matcher(canonicalUrl);
                    if (mCanon.find()) {
                        newsid = mCanon.group(1);
                    } else {
                        Pattern pId = Pattern.compile("/(\\d+)-");
                        Matcher mCanonId = pId.matcher(canonicalUrl);
                        if (mCanonId.find()) {
                            newsid = mCanonId.group(1);
                        }
                    }
                }
            }

            if (newsid.isEmpty()) {
                Element ogUrl = doc.selectFirst("meta[property=og:url]");
                if (ogUrl != null) {
                    String ogUrlVal = ogUrl.attr("content");
                    Matcher mOg = NEWSID_PATTERN.matcher(ogUrlVal);
                    if (mOg.find()) {
                        newsid = mOg.group(1);
                    } else {
                        Pattern pId = Pattern.compile("/(\\d+)-");
                        Matcher mOgId = pId.matcher(ogUrlVal);
                        if (mOgId.find()) {
                            newsid = mOgId.group(1);
                        }
                    }
                }
            }

            // === EPISODES ===
            List<Episode> episodes = new ArrayList<>();
            if (!newsid.isEmpty()) {
                String apiUrl = baseUrl + "/engine/ajax/manga_episodes_api.php?id=" + newsid;
                try {
                    log.info("Appel de l'API épisodes: {}", apiUrl);
                    Connection.Response response = Jsoup.connect(apiUrl)
                            .userAgent(userAgent)
                            .timeout(connectionTimeout)
                            .referrer(animeUrl)
                            .ignoreContentType(true)
                            .execute();
                    String json = response.body();
                    episodes = parseEpisodesFromJson(json, animeUrl);
                } catch (Exception e) {
                    log.warn("L'appel à l'API épisodes a échoué, repli sur le parsing DOM: {}", e.getMessage());
                }
            }

            if (episodes.isEmpty()) {
                episodes = parseEpisodes(doc, animeUrl);
            }
            detail.setEpisodes(episodes);

            log.info("Anime '{}' - {} épisodes trouvés", detail.getTitle(), detail.getEpisodes().size());
            return detail;

        } catch (IOException e) {
            log.error("Erreur chargement anime: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse les épisodes à partir du JSON retourné par l'API.
     */
    private List<Episode> parseEpisodesFromJson(String json, String animeUrl) {
        List<Episode> episodes = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            
            String[] versions = {"vf", "vostfr"};
            for (String versionKey : versions) {
                JsonNode versionNode = root.get(versionKey);
                if (versionNode == null || !versionNode.isObject()) continue;
                
                String displayVersion = versionKey.toUpperCase();
                
                Iterator<Map.Entry<String, JsonNode>> fields = versionNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String epNumStr = field.getKey();
                    JsonNode playersNode = field.getValue();
                    
                    if (playersNode == null || !playersNode.isObject()) continue;
                    
                    int epNum;
                    try {
                        epNum = Integer.parseInt(epNumStr);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    
                    String playerUrl = "";
                    String downloadPageUrl = "";
                    
                    Iterator<Map.Entry<String, JsonNode>> players = playersNode.fields();
                    while (players.hasNext()) {
                        Map.Entry<String, JsonNode> pEntry = players.next();
                        String pName = pEntry.getKey().toLowerCase();
                        String pUrl = pEntry.getValue().asText();
                        
                        if (pUrl.isEmpty()) continue;
                        
                        if (playerUrl.isEmpty()) {
                            playerUrl = pUrl;
                        }
                        
                        if (pName.contains("vidzy") || pUrl.contains("vidzy")) {
                            playerUrl = pUrl;
                            break;
                        }
                    }
                    
                    if (!playerUrl.isEmpty()) {
                        if (playerUrl.contains("vidzy")) {
                            downloadPageUrl = playerUrl.replace("/embed-", "/d/").replace(".html", "").replace("/e/", "/d/");
                            if (!downloadPageUrl.contains("_n")) {
                                downloadPageUrl = downloadPageUrl + "_n";
                            }
                        }
                        
                        String epTitle = "Épisode " + epNum;
                        JsonNode infoNode = root.get("info");
                        if (infoNode != null && infoNode.has(epNumStr)) {
                            JsonNode epInfo = infoNode.get(epNumStr);
                            if (epInfo.has("title") && !epInfo.get("title").asText().isEmpty()) {
                                epTitle = epInfo.get("title").asText();
                            }
                        }
                        
                        Episode ep = Episode.builder()
                                .number(epNum)
                                .title(epTitle)
                                .playerUrl(playerUrl)
                                .downloadPageUrl(downloadPageUrl)
                                .version(displayVersion)
                                .build();
                        episodes.add(ep);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erreur lors du parsing JSON des épisodes: {}", e.getMessage());
        }
        
        episodes.sort((a, b) -> {
            int vComp = a.getVersion().compareTo(b.getVersion());
            if (vComp != 0) return vComp;
            return Integer.compare(a.getNumber(), b.getNumber());
        });
        
        return episodes;
    }

    /**
     * Parse les métadonnées (genre, studio, directeur, casting).
     */
    private void parseMetadata(Document doc, AnimeDetail detail) {
        // Titre original
        Elements allText = doc.select(".full-story li, .fstory li, .movie-info li, .finfo li, .full-story span, .fstory span");
        for (Element el : allText) {
            String text = el.text();
            if (text.contains("Titre Original")) {
                detail.setOriginalTitle(text.replaceAll(".*:", "").trim());
            }
            if (text.contains("Studio")) {
                detail.setStudio(text.replaceAll(".*:", "").trim());
            }
        }

        // Genres
        Elements genreLinks = doc.select("a[href*=manga_genre]");
        List<String> genres = new ArrayList<>();
        for (Element g : genreLinks) {
            String genre = g.text().trim();
            if (!genre.isBlank() && !genres.contains(genre)) {
                genres.add(genre);
            }
        }
        detail.setGenres(genres);

        // Version
        Element versionEl = doc.selectFirst("a[href*=version-serie]");
        if (versionEl != null) {
            detail.setVersion(versionEl.text().trim());
        }

        // Directors
        Elements dirLinks = doc.select("a[href*=director]");
        List<String> directors = new ArrayList<>();
        for (Element d : dirLinks) {
            String name = d.text().trim();
            if (!name.isBlank() && !directors.contains(name)) directors.add(name);
        }
        detail.setDirectors(directors);

        // Actors
        Elements actorLinks = doc.select("a[href*=actors]");
        List<String> actors = new ArrayList<>();
        for (Element a : actorLinks) {
            String name = a.text().trim();
            if (!name.isBlank() && !actors.contains(name)) actors.add(name);
        }
        detail.setActors(actors);
    }

    /**
     * Parse la liste des épisodes depuis la page anime.
     * Le site DLE embarque les URLs des épisodes dans des tableaux JavaScript.
     * Structure typique:
     * - var defined_vostfr = ["url1","url2",...];
     * - var defined_vf = ["url1","url2",...];
     */
    private List<Episode> parseEpisodes(Document doc, String animeUrl) {
        List<Episode> episodes = new ArrayList<>();

        // Déterminer la version globale de l'anime
        String globalVersion = "";
        Element versionEl = doc.selectFirst("a[href*=version-serie]");
        if (versionEl != null) {
            globalVersion = versionEl.text().trim();
        }

        // ====== STRATÉGIE 1: Extraire les URLs depuis les scripts JavaScript ======
        // Le site stocke les URLs des épisodes dans des variables JS
        Elements scripts = doc.select("script");
        Map<String, List<String>> versionUrls = new LinkedHashMap<>();

        for (Element script : scripts) {
            String data = script.data();
            if (data.isBlank()) continue;

            // Chercher les tableaux JS: var defined_vostfr = [...], var defined_vf = [...]
            Matcher arrayMatcher = JS_ARRAY_PATTERN.matcher(data);
            while (arrayMatcher.find()) {
                String varName = arrayMatcher.group(1).toLowerCase();
                String arrayContent = arrayMatcher.group(2);

                String version = "VOSTFR";
                if (varName.contains("vf") && !varName.contains("vostfr")) {
                    version = "VF";
                } else if (varName.contains("vostfr")) {
                    version = "VOSTFR";
                }

                List<String> urls = extractUrlsFromJsArray(arrayContent);
                if (!urls.isEmpty()) {
                    versionUrls.put(version, urls);
                    log.debug("Trouvé {} URLs pour {} (var: {})", urls.size(), version, varName);
                }
            }

            // Chercher aussi les URLs vidzy individuelles dans le JS
            if (versionUrls.isEmpty()) {
                Matcher vidzyMatcher = VIDZY_URL_PATTERN.matcher(data);
                List<String> vidzyUrls = new ArrayList<>();
                while (vidzyMatcher.find()) {
                    String vidzyUrl = vidzyMatcher.group(1);
                    if (!vidzyUrls.contains(vidzyUrl)) {
                        vidzyUrls.add(vidzyUrl);
                    }
                }
                if (!vidzyUrls.isEmpty()) {
                    String version = !globalVersion.isEmpty() ? globalVersion : "VOSTFR";
                    versionUrls.put(version, vidzyUrls);
                }
            }
        }

        // Si on a trouvé des URLs dans les scripts JS
        if (!versionUrls.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : versionUrls.entrySet()) {
                String version = entry.getKey();
                List<String> urls = entry.getValue();

                for (int i = 0; i < urls.size(); i++) {
                    String url = urls.get(i);
                    int epNum = i + 1;

                    // Déterminer si c'est un lien vidzy
                    String playerUrl = url;
                    String downloadPageUrl = "";

                    if (url.contains("vidzy.live/d/")) {
                        downloadPageUrl = url;
                    } else if (url.contains("vidzy.live")) {
                        // Convertir l'URL streaming en URL download si possible
                        playerUrl = url;
                        downloadPageUrl = url.replace("/e/", "/d/");
                    }

                    Episode ep = Episode.builder()
                            .number(epNum)
                            .title("Épisode " + epNum)
                            .playerUrl(playerUrl)
                            .downloadPageUrl(downloadPageUrl)
                            .version(version)
                            .build();

                    episodes.add(ep);
                }
            }
        }

        // ====== STRATÉGIE 2: Chercher les liens directement dans le HTML ======
        if (episodes.isEmpty()) {
            Elements episodeLinks = doc.select(
                    "a[href*=vidzy], a[href*=luluvdo], " +
                    ".ep-title a[href], .episode a[href], " +
                    "a.btn-download[href], a[href*='/d/'], a[href*='/e/']"
            );

            Set<String> seenUrls = new HashSet<>();
            int epNum = 1;

            for (Element link : episodeLinks) {
                String href = link.absUrl("href");
                if (href.isBlank() || seenUrls.contains(href)) continue;
                if (href.contains("xfsearch") || href.contains("manga_genre")) continue;
                seenUrls.add(href);

                String text = link.text().trim();
                if (text.isBlank()) text = link.attr("title").trim();

                // Extraire le numéro d'épisode du texte
                Pattern epPattern = Pattern.compile("(?:Episode|Ep|EP|episode|ep|Épisode)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                Matcher m = epPattern.matcher(text);
                int num = epNum;
                if (m.find()) {
                    num = Integer.parseInt(m.group(1));
                }

                String downloadPageUrl = href.contains("vidzy") ? href : "";
                String playerUrl = href;

                Episode ep = Episode.builder()
                        .number(num)
                        .title(text.isBlank() ? "Épisode " + num : text)
                        .playerUrl(playerUrl)
                        .downloadPageUrl(downloadPageUrl)
                        .version(!globalVersion.isEmpty() ? globalVersion : "")
                        .build();

                episodes.add(ep);
                epNum = num + 1;
            }
        }

        // ====== STRATÉGIE 3: Chercher les épisodes dans les éléments .ep-title ======
        if (episodes.isEmpty()) {
            Elements epTitles = doc.select(".ep-title");
            int epNum = 1;

            for (Element epTitle : epTitles) {
                String text = epTitle.text().trim();
                Pattern epPattern = Pattern.compile("\\d+");
                Matcher m = epPattern.matcher(text);
                int num = epNum;
                if (m.find()) {
                    num = Integer.parseInt(m.group());
                }

                Episode ep = Episode.builder()
                        .number(num)
                        .title(text.isBlank() ? "Épisode " + num : text)
                        .playerUrl("")
                        .downloadPageUrl("")
                        .version(!globalVersion.isEmpty() ? globalVersion : "")
                        .build();

                episodes.add(ep);
                epNum = num + 1;
            }
        }

        // Trier par version puis par numéro d'épisode
        episodes.sort((a, b) -> {
            int vComp = a.getVersion().compareTo(b.getVersion());
            if (vComp != 0) return vComp;
            return Integer.compare(a.getNumber(), b.getNumber());
        });

        return episodes;
    }

    /**
     * Extrait les URLs depuis le contenu d'un tableau JavaScript.
     * Format attendu: "url1","url2","url3"
     */
    private List<String> extractUrlsFromJsArray(String arrayContent) {
        List<String> urls = new ArrayList<>();

        // Extraire chaque URL entre guillemets
        Pattern urlPattern = Pattern.compile("['\"]([^'\"]+)['\"]");
        Matcher matcher = urlPattern.matcher(arrayContent);

        while (matcher.find()) {
            String url = matcher.group(1).trim();
            if (!url.isBlank() && (url.startsWith("http") || url.startsWith("//"))) {
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
                urls.add(url);
            }
        }

        return urls;
    }

    /**
     * Extrait le lien de téléchargement direct depuis une page vidzy.live.
     * Le site redirige vers une page de type: https://vidzy.live/d/CODE.html
     * qui contient le lien direct vers le fichier .mp4.
     */
    public Map<String, String> extractDownloadInfo(String vidzyUrl) {
        log.info("Extraction lien direct depuis: {}", vidzyUrl);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("videoUrl", vidzyUrl);
        result.put("subtitleUrl", "");

        try {
            // Étape 1 : Extraire le videoId et le domaine de base
            String domain = "https://vidzy.live";
            Pattern pDomain = Pattern.compile("(https?://[^/]+)");
            Matcher mDomain = pDomain.matcher(vidzyUrl);
            if (mDomain.find()) {
                domain = mDomain.group(1);
            }

            String videoId = "";
            if (vidzyUrl.contains("embed-")) {
                int start = vidzyUrl.indexOf("embed-") + 6;
                int end = vidzyUrl.indexOf(".html", start);
                if (end != -1) {
                    videoId = vidzyUrl.substring(start, end);
                } else {
                    videoId = vidzyUrl.substring(start);
                }
            } else {
                int lastSlash = vidzyUrl.lastIndexOf('/');
                if (lastSlash != -1) {
                    videoId = vidzyUrl.substring(lastSlash + 1)
                            .replace("_n", "")
                            .replace("_o", "")
                            .replace(".html", "");
                }
            }

            if (videoId.isEmpty()) {
                log.warn("Impossible d'extraire le video_id de l'URL {}", vidzyUrl);
                return result;
            }

            String embedUrl = domain + "/embed-" + videoId + ".html";
            String downloadUrl = domain + "/d/" + videoId + "_n";

            // Étape 2 : Charger la page iframe (embed) de Vidzy pour décoder les sous-titres et informations
            log.info("Chargement de la page d'embed pour les sous-titres: {}", embedUrl);
            Document embedDoc = Jsoup.connect(embedUrl)
                    .userAgent(userAgent)
                    .timeout(connectionTimeout)
                    .referrer(baseUrl)
                    .ignoreHttpErrors(true)
                    .get();
            String embedHtml = embedDoc.html();

            // Tenter de décoder le JS compressé via l'unpacker
            Pattern pPacker = Pattern.compile("eval\\s*\\(\\s*function\\s*\\(p,\\s*a,\\s*c,\\s*k,\\s*e,\\s*d\\s*\\).*?\\}\\s*\\(\\s*['\"](.*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"]([^'\"]*)['\"]\\.split\\s*\\(\\s*['\"]\\|['\"]\\s*\\)\\s*\\)\\s*\\)");
            Matcher mPacker = pPacker.matcher(embedHtml);
            String unpackedJs = "";
            if (mPacker.find()) {
                String p = mPacker.group(1);
                int a = Integer.parseInt(mPacker.group(2));
                int c = Integer.parseInt(mPacker.group(3));
                String[] k = mPacker.group(4).split("\\|");
                log.info("Bloc JS Dean Edwards packé trouvé ! Décompression...");
                try {
                    unpackedJs = unpack(p, a, c, k);
                } catch (Exception e) {
                    log.error("Erreur de décompression Unpacker: {}", e.getMessage());
                }
            }

            String searchSource = unpackedJs.isEmpty() ? embedHtml : unpackedJs;
            String subtitleUrl = "";

            // Chercher le proxy des sous-titres français (.vtt)
            Pattern pSub = Pattern.compile("(https?://[^'\"\\s]+fre\\.vtt[^'\"\\s]*)", Pattern.CASE_INSENSITIVE);
            Matcher mSub = pSub.matcher(searchSource);
            if (mSub.find()) {
                subtitleUrl = mSub.group(1).replace("\\'", "'").replace("\\\"", "\"");
                log.info("Proxy de sous-titres français trouvé: {}", subtitleUrl);
            }

            // Fallback: chercher n'importe quel fichier de sous-titres
            if (subtitleUrl.isEmpty()) {
                Pattern pVtt = Pattern.compile("(https?://[^'\"\\s]+\\.(?:vtt|srt)[^'\"\\s]*)", Pattern.CASE_INSENSITIVE);
                Matcher mVtt = pVtt.matcher(searchSource);
                if (mVtt.find()) {
                    subtitleUrl = mVtt.group(1).replace("\\'", "'").replace("\\\"", "\"");
                    log.info("Sous-titres trouvés: {}", subtitleUrl);
                }
            }

            // Fallback 2: chercher les balises <track> dans le DOM
            if (subtitleUrl.isEmpty()) {
                Elements htmlTracks = embedDoc.select("track[src]");
                for (Element track : htmlTracks) {
                    String kind = track.attr("kind").toLowerCase();
                    if (kind.contains("subtitle") || kind.contains("caption")) {
                        subtitleUrl = track.absUrl("src");
                        if (!subtitleUrl.isEmpty()) {
                            log.info("Balise track sous-titres trouvée: {}", subtitleUrl);
                            break;
                        }
                    }
                }
            }

            result.put("subtitleUrl", subtitleUrl);

            // Étape 3 : Charger la page de téléchargement pour extraire les paramètres POST
            log.info("Chargement de la page de téléchargement: {}", downloadUrl);
            Document downloadDoc = Jsoup.connect(downloadUrl)
                    .userAgent(userAgent)
                    .timeout(connectionTimeout)
                    .referrer(embedUrl)
                    .ignoreHttpErrors(true)
                    .get();

            String op = "download_orig";
            String id = videoId;
            String mode = "n";
            String hash = "";

            Element form = downloadDoc.selectFirst("form#F1, form[action]");
            if (form != null) {
                Element opEl = form.selectFirst("input[name=op]");
                if (opEl != null) op = opEl.attr("value");

                Element idEl = form.selectFirst("input[name=id]");
                if (idEl != null) id = idEl.attr("value");

                Element modeEl = form.selectFirst("input[name=mode]");
                if (modeEl != null) mode = modeEl.attr("value");

                Element hashEl = form.selectFirst("input[name=hash]");
                if (hashEl != null) hash = hashEl.attr("value");
            } else {
                Pattern pHash = Pattern.compile("name=['\"]hash['\"]\\s+value=['\"]([^'\"]+)['\"]");
                Matcher mHash = pHash.matcher(downloadDoc.html());
                if (mHash.find()) {
                    hash = mHash.group(1);
                }
            }

            // Étape 4 : Soumettre la requête POST pour intercepter la redirection 302
            if (!hash.isEmpty()) {
                log.info("Jeton hash trouvé : '{}'. Envoi de la requête POST pour obtenir le flux .mp4...", hash);
                Connection.Response postResp = Jsoup.connect(downloadUrl)
                        .userAgent(userAgent)
                        .timeout(connectionTimeout)
                        .method(Connection.Method.POST)
                        .data("op", op)
                        .data("id", id)
                        .data("mode", mode)
                        .data("hash", hash)
                        .referrer(downloadUrl)
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .execute();

                if (postResp.statusCode() == 302 || postResp.statusCode() == 301) {
                    String directUrl = postResp.header("Location");
                    if (directUrl != null && !directUrl.isEmpty()) {
                        log.info("Lien de flux direct MP4 résolu (302): {}", directUrl);
                        result.put("videoUrl", directUrl);
                    }
                } else if (postResp.statusCode() == 200) {
                    log.info("Requête POST a retourné un code 200. Extraction du lien depuis le corps HTML...");
                    String postHtml = postResp.body();
                    Document postDoc = postResp.parse();
                    
                    // Chercher dans les liens a[href]
                    Elements links = postDoc.select("a[href*=.mp4], a[href*=.mkv], a[href*=.avi]");
                    String directUrl = "";
                    for (Element link : links) {
                        String href = link.attr("href");
                        if (!href.isEmpty()) {
                            directUrl = href;
                            break;
                        }
                    }
                    
                    // Fallback regexp
                    if (directUrl.isEmpty()) {
                        Pattern pDirect = Pattern.compile("href=['\"](https?://[^'\"]+\\.(?:mp4|mkv|avi)[^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
                        Matcher mDirect = pDirect.matcher(postHtml);
                        if (mDirect.find()) {
                            directUrl = mDirect.group(1);
                        }
                    }
                    
                    if (!directUrl.isEmpty()) {
                        log.info("Lien de flux direct MP4 extrait avec succès (200): {}", directUrl);
                        result.put("videoUrl", directUrl);
                    } else {
                        log.warn("Impossible d'extraire le lien direct depuis le corps de la réponse 200.");
                    }
                } else {
                    log.warn("La requête POST a renvoyé un code statut inattendu : {}", postResp.statusCode());
                }
            } else {
                log.warn("Impossible de résoudre le lien direct: jeton de sécurité hash non trouvé sur la page.");
            }

        } catch (IOException e) {
            log.error("Erreur lors de la résolution du lien de téléchargement direct: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Wrapper simplifié qui retourne uniquement l'URL de téléchargement.
     */
    public String extractDirectDownloadUrl(String vidzyUrl) {
        Map<String, String> info = extractDownloadInfo(vidzyUrl);
        return info.get("videoUrl");
    }

    /**
     * Récupère un document HTML avec les headers appropriés.
     */
    private Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(connectionTimeout)
                .referrer(baseUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip, deflate")
                .followRedirects(true)
                .get();
    }

    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static String baseN(int num, int radix) {
        if (num == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(DIGITS.charAt(num % radix));
            num /= radix;
        }
        return sb.reverse().toString();
    }

    public static String unpack(String p, int a, int c, String[] k) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < c; i++) {
            String key = baseN(i, a);
            String val = (i < k.length && !k[i].isEmpty()) ? k[i] : key;
            map.put(key, val);
        }
        Pattern pattern = Pattern.compile("\\b[0-9a-zA-Z_]+\\b");
        Matcher matcher = pattern.matcher(p);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String word = matcher.group();
            String replacement = map.get(word);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : word));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

