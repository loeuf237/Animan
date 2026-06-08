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
import java.util.regex.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service de scraping pour french-manga.net (source principale).
 * Inclut le fallback automatique vers voir-anime.to si french-manga n'a pas le titre.
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

    @org.springframework.beans.factory.annotation.Autowired
    private ProxyRotatorService proxyRotator;

    private final List<AnimeResult> latestCache = new CopyOnWriteArrayList<>();
    private long lastCacheTime = 0;
    private boolean isRefreshing = false;

    @PostConstruct
    public void init() {
        log.info("Pré-chargement asynchrone du cache des dernières sorties (french-manga)...");
        CompletableFuture.runAsync(this::getLatestReleases);
    }

    public List<AnimeResult> getLatestReleases() {
        if (latestCache.isEmpty()) {
            synchronized (latestCache) {
                if (latestCache.isEmpty()) {
                    log.info("Initialisation synchrone du cache des dernières sorties (french-manga)...");
                    refreshCacheSync();
                }
            }
        } else if (System.currentTimeMillis() - lastCacheTime > 15 * 60 * 1000) {
            triggerBackgroundRefresh();
        }
        return new ArrayList<>(latestCache);
    }

    private void refreshCacheSync() {
        try {
            log.info("Chargement des dernières sorties depuis {}", baseUrl);
            Document doc = fetchDocument(baseUrl);
            List<AnimeResult> results = parseSearchResults(doc);
            if (!results.isEmpty()) {
                latestCache.clear();
                latestCache.addAll(results);
                lastCacheTime = System.currentTimeMillis();
                log.info("Cache des dernières sorties mis à jour (french-manga: {} animés)", latestCache.size());
            }
        } catch (Exception e) {
            log.error("Erreur lors de la mise à jour synchrone du cache (french-manga): {}", e.getMessage());
        }
    }

    private synchronized void triggerBackgroundRefresh() {
        if (isRefreshing) return;
        isRefreshing = true;
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Mise à jour asynchrone du cache des dernières sorties (french-manga)...");
                Document doc = fetchDocument(baseUrl);
                List<AnimeResult> results = parseSearchResults(doc);
                if (!results.isEmpty()) {
                    latestCache.clear();
                    latestCache.addAll(results);
                    lastCacheTime = System.currentTimeMillis();
                    log.info("Mise à jour asynchrone du cache réussie (french-manga: {} animés)", latestCache.size());
                }
            } catch (Exception e) {
                log.error("Erreur lors de la mise à jour asynchrone du cache (french-manga): {}", e.getMessage());
            } finally {
                isRefreshing = false;
            }
        });
    }

    private Connection connect(String url) {
        String ua = proxyRotator != null ? proxyRotator.getUserAgent() : userAgent;
        java.net.Proxy proxy = proxyRotator != null ? proxyRotator.getRandomProxy() : null;
        Connection conn = Jsoup.connect(url).userAgent(ua).timeout(connectionTimeout);
        if (proxy != null) {
            conn.proxy(proxy);
        }
        return conn;
    }

    private static final Pattern NEWSID_PATTERN = Pattern.compile("newsid=(\\d+)");
    private static final Pattern JS_ARRAY_PATTERN =
            Pattern.compile("var\\s+(defined_\\w+)\\s*=\\s*\\[([^\\]]+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDZY_URL_PATTERN =
            Pattern.compile("(https?://(?:vidzy\\.live|luluvdo\\.com)[^\"'\\s]+)", Pattern.CASE_INSENSITIVE);

    // =========================================================================
    // RECHERCHE
    // =========================================================================

    public List<AnimeResult> searchAnime(String query) {
        log.info("Recherche anime: '{}'", query);
        List<AnimeResult> results = new ArrayList<>();

        try {
            Connection.Response response = connect(baseUrl + "/engine/ajax/search.php")
                    .method(Connection.Method.POST)
                    .data("query", query)
                    .data("page", "1")
                    .referrer(baseUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .followRedirects(true)
                    .execute();

            Document doc = response.parse();
            results = parseSearchResults(doc);

            if (results.isEmpty()) {
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

    private List<AnimeResult> parseSearchResults(Document doc) {
        List<AnimeResult> results = new ArrayList<>();
        Elements articles = doc.select(".short, .mov, .shortstory, .short-story, article, .anime-card, .movie-item, .story-item, .search-item");

        if (articles.isEmpty()) {
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
                    if (!isDuplicate) results.add(result);
                }
            } catch (Exception e) {
                log.debug("Erreur parsing carte anime: {}", e.getMessage());
            }
        }
        return results;
    }

    private AnimeResult parseAnimeCard(Element card) {
        String url = "", title = "", imageUrl = "", version = "", type = "Anime", episodeInfo = "", id = "";

        if (card.hasClass("search-item") || card.hasAttr("onclick")) {
            String onclick = card.attr("onclick");
            Pattern p = Pattern.compile("location\\.href\\s*=\\s*['\"]([^'\"]+)['\"]");
            Matcher m = p.matcher(onclick);
            if (m.find()) {
                url = m.group(1);
                if (url.startsWith("/")) url = baseUrl + url;
            } else return null;

            Element titleEl = card.selectFirst(".search-title");
            if (titleEl != null) title = titleEl.text().trim();

            Element img = card.selectFirst(".search-poster img");
            if (img != null) {
                imageUrl = img.absUrl("src");
                if (imageUrl.isBlank()) {
                    imageUrl = img.attr("src");
                    if (imageUrl.startsWith("/")) imageUrl = baseUrl + imageUrl;
                }
            }
        } else {
            Element link = card.selectFirst("a[href*=newsid]");
            if (link == null) link = card.selectFirst("a[href$=.html]:not([href*=xfsearch]):not([href*=do=search])");
            if (link == null) return null;

            url = link.absUrl("href");
            if (url.isBlank() || url.contains("xfsearch") || url.contains("do=search")) return null;

            Element titleEl = card.selectFirst("h2, h3, h4, .title, .movie-title, .short-title");
            if (titleEl != null) title = titleEl.text().trim();
            else title = link.attr("title").isBlank() ? link.text().trim() : link.attr("title").trim();

            Element img = card.selectFirst("img[src]");
            if (img != null) {
                imageUrl = img.absUrl("src");
                if (imageUrl.isBlank()) imageUrl = img.absUrl("data-src");
            }

            Element versionEl = card.selectFirst("a[href*=version-serie], .version, .quality");
            if (versionEl != null) version = versionEl.text().trim();

            Element typeEl = card.selectFirst("a[href*='xf=Film'], a[href*='xf=Anime']");
            if (typeEl != null) type = typeEl.text().trim();
        }

        if (title.isBlank() || title.length() < 2) return null;

        Matcher m = NEWSID_PATTERN.matcher(url);
        if (m.find()) {
            id = m.group(1);
        } else {
            Pattern pId = Pattern.compile("/(\\d+)-");
            Matcher mId = pId.matcher(url);
            id = mId.find() ? mId.group(1) : UUID.randomUUID().toString().substring(0, 8);
        }

        return AnimeResult.builder()
                .id(id).title(title).imageUrl(imageUrl).url(url)
                .version(version).type(type).episodeInfo(episodeInfo)
                .build();
    }

    private List<AnimeResult> searchViaListing(String query) throws IOException {
        List<AnimeResult> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (int page = 1; page <= 5; page++) {
            String url = page == 1 ? baseUrl + "/manga-streaming-1/"
                    : baseUrl + "/index.php?cstart=" + page + "&do=cat&category=manga-streaming-1";
            Document doc = fetchDocument(url);
            List<AnimeResult> pageResults = parseSearchResults(doc);
            for (AnimeResult result : pageResults) {
                if (result.getTitle().toLowerCase().contains(lowerQuery)) results.add(result);
            }
            if (results.size() >= 20) break;
        }
        return results;
    }

    // =========================================================================
    // DETAILS ANIME
    // =========================================================================

    public AnimeDetail getAnimeDetail(String animeUrl) {
        log.info("Chargement détails anime: {}", animeUrl);
        try {
            Document doc = fetchDocument(animeUrl);
            AnimeDetail detail = new AnimeDetail();
            detail.setUrl(animeUrl);

            Element titleEl = doc.selectFirst("h1, .full-title, .movie-title, #news-title");
            detail.setTitle(titleEl != null ? titleEl.text().trim()
                    : doc.title().replaceAll("\\|.*", "").trim());

            Element posterImg = doc.selectFirst(".full-story img[src], .fstory img[src], .movie-poster img[src], .poster img[src], .short-img img[src]");
            if (posterImg != null) detail.setImageUrl(posterImg.absUrl("src"));

            Element synopsisEl = doc.selectFirst(".full-story .full-text, .fstory .ftext, .movie-description, .story-text, .fdesc");
            if (synopsisEl != null) {
                detail.setSynopsis(synopsisEl.text().trim());
            } else {
                Elements paragraphs = doc.select(".full-story p, .fstory p, #dle-content p");
                StringBuilder sb = new StringBuilder();
                for (Element p : paragraphs) {
                    String text = p.text().trim();
                    if (text.length() > 50 && !text.contains("Genre") && !text.contains("Studio")) sb.append(text).append(" ");
                }
                if (!sb.isEmpty()) detail.setSynopsis(sb.toString().trim());
            }

            parseMetadata(doc, detail);

            String newsid = extractNewsId(animeUrl, doc);
            List<Episode> episodes = new ArrayList<>();

            if (!newsid.isEmpty()) {
                String apiUrl = baseUrl + "/engine/ajax/manga_episodes_api.php?id=" + newsid;
                try {
                    log.info("Appel de l'API épisodes: {}", apiUrl);
                    Connection.Response response = connect(apiUrl)
                            .referrer(animeUrl).ignoreContentType(true).execute();
                    episodes = parseEpisodesFromJson(response.body(), animeUrl);
                } catch (Exception e) {
                    log.warn("API épisodes échouée, repli DOM: {}", e.getMessage());
                }
            }

            if (episodes.isEmpty()) episodes = parseEpisodes(doc, animeUrl);
            detail.setEpisodes(episodes);

            log.info("Anime '{}' - {} épisode(s)", detail.getTitle(), detail.getEpisodes().size());
            return detail;

        } catch (IOException e) {
            log.error("Erreur chargement anime: {}", e.getMessage());
            return null;
        }
    }

    private String extractNewsId(String animeUrl, Document doc) {
        Matcher m = NEWSID_PATTERN.matcher(animeUrl);
        if (m.find()) return m.group(1);
        Pattern pId = Pattern.compile("/(\\d+)-");
        Matcher mId = pId.matcher(animeUrl);
        if (mId.find()) return mId.group(1);

        Element canonical = doc.selectFirst("link[rel=canonical]");
        if (canonical != null) {
            String cUrl = canonical.attr("href");
            Matcher mc = NEWSID_PATTERN.matcher(cUrl);
            if (mc.find()) return mc.group(1);
            Matcher mcId = pId.matcher(cUrl);
            if (mcId.find()) return mcId.group(1);
        }
        return "";
    }

    // =========================================================================
    // PARSING EPISODES depuis JSON API
    // =========================================================================

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
                    try { epNum = Integer.parseInt(epNumStr); }
                    catch (NumberFormatException e) { continue; }

                    String playerUrl = "";
                    String downloadPageUrl = "";
                    Iterator<Map.Entry<String, JsonNode>> players = playersNode.fields();

                    while (players.hasNext()) {
                        Map.Entry<String, JsonNode> pEntry = players.next();
                        String pName = pEntry.getKey().toLowerCase();
                        String pUrl = pEntry.getValue().asText();
                        if (pUrl.isEmpty()) continue;
                        if (playerUrl.isEmpty()) playerUrl = pUrl;
                        if (pName.contains("vidzy") || pUrl.contains("vidzy")) { playerUrl = pUrl; break; }
                    }

                    if (!playerUrl.isEmpty()) {
                        if (playerUrl.contains("vidzy")) {
                            downloadPageUrl = playerUrl.replace("/embed-", "/d/")
                                    .replace(".html", "").replace("/e/", "/d/");
                            if (!downloadPageUrl.contains("_n")) downloadPageUrl += "_n";
                        }

                        String epTitle = "Épisode " + epNum;
                        JsonNode infoNode = root.get("info");
                        if (infoNode != null && infoNode.has(epNumStr)) {
                            JsonNode epInfo = infoNode.get(epNumStr);
                            if (epInfo.has("title") && !epInfo.get("title").asText().isEmpty())
                                epTitle = epInfo.get("title").asText();
                        }

                        episodes.add(Episode.builder()
                                .number(epNum).title(epTitle).playerUrl(playerUrl)
                                .downloadPageUrl(downloadPageUrl).version(displayVersion)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erreur parsing JSON épisodes: {}", e.getMessage());
        }
        episodes.sort((a, b) -> {
            int vComp = a.getVersion().compareTo(b.getVersion());
            return vComp != 0 ? vComp : Integer.compare(a.getNumber(), b.getNumber());
        });
        return episodes;
    }

    // =========================================================================
    // PARSING EPISODES depuis DOM
    // =========================================================================

    private List<Episode> parseEpisodes(Document doc, String animeUrl) {
        List<Episode> episodes = new ArrayList<>();
        String globalVersion = "";
        Element versionEl = doc.selectFirst("a[href*=version-serie]");
        if (versionEl != null) globalVersion = versionEl.text().trim();

        Elements scripts = doc.select("script");
        Map<String, List<String>> versionUrls = new LinkedHashMap<>();

        for (Element script : scripts) {
            String data = script.data();
            if (data.isBlank()) continue;
            Matcher arrayMatcher = JS_ARRAY_PATTERN.matcher(data);
            while (arrayMatcher.find()) {
                String varName = arrayMatcher.group(1).toLowerCase();
                String arrayContent = arrayMatcher.group(2);
                String version = varName.contains("vf") && !varName.contains("vostfr") ? "VF" : "VOSTFR";
                List<String> urls = extractUrlsFromJsArray(arrayContent);
                if (!urls.isEmpty()) versionUrls.put(version, urls);
            }

            if (versionUrls.isEmpty()) {
                Matcher vidzyMatcher = VIDZY_URL_PATTERN.matcher(data);
                List<String> vidzyUrls = new ArrayList<>();
                while (vidzyMatcher.find()) {
                    String vidzyUrl = vidzyMatcher.group(1);
                    if (!vidzyUrls.contains(vidzyUrl)) vidzyUrls.add(vidzyUrl);
                }
                if (!vidzyUrls.isEmpty()) {
                    versionUrls.put(globalVersion.isEmpty() ? "VOSTFR" : globalVersion, vidzyUrls);
                }
            }
        }

        if (!versionUrls.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : versionUrls.entrySet()) {
                String version = entry.getKey();
                List<String> urls = entry.getValue();
                for (int i = 0; i < urls.size(); i++) {
                    String url = urls.get(i);
                    String downloadPageUrl = url.contains("vidzy.live/d/") ? url
                            : url.contains("vidzy.live") ? url.replace("/e/", "/d/") : "";
                    episodes.add(Episode.builder()
                            .number(i + 1).title("Épisode " + (i + 1))
                            .playerUrl(url).downloadPageUrl(downloadPageUrl).version(version)
                            .build());
                }
            }
        }

        if (episodes.isEmpty()) {
            Elements episodeLinks = doc.select("a[href*=vidzy], a[href*=luluvdo], .ep-title a[href], a.btn-download[href], a[href*='/d/']");
            Set<String> seenUrls = new HashSet<>();
            int epNum = 1;
            for (Element link : episodeLinks) {
                String href = link.absUrl("href");
                if (href.isBlank() || seenUrls.contains(href)) continue;
                if (href.contains("xfsearch") || href.contains("manga_genre")) continue;
                seenUrls.add(href);
                String text = link.text().trim();
                Pattern epPattern = Pattern.compile("(?:Episode|Ep|EP|Épisode)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                Matcher m = epPattern.matcher(text);
                int num = epNum;
                if (m.find()) num = Integer.parseInt(m.group(1));
                episodes.add(Episode.builder()
                        .number(num).title(text.isBlank() ? "Épisode " + num : text)
                        .playerUrl(href).downloadPageUrl(href.contains("vidzy") ? href : "")
                        .version(globalVersion.isEmpty() ? "" : globalVersion)
                        .build());
                epNum = num + 1;
            }
        }

        episodes.sort((a, b) -> {
            int vComp = a.getVersion().compareTo(b.getVersion());
            return vComp != 0 ? vComp : Integer.compare(a.getNumber(), b.getNumber());
        });
        return episodes;
    }

    private List<String> extractUrlsFromJsArray(String arrayContent) {
        List<String> urls = new ArrayList<>();
        Pattern urlPattern = Pattern.compile("['\"]([^'\"]+)['\"]");
        Matcher matcher = urlPattern.matcher(arrayContent);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            if (!url.isBlank() && (url.startsWith("http") || url.startsWith("//"))) {
                if (url.startsWith("//")) url = "https:" + url;
                urls.add(url);
            }
        }
        return urls;
    }

    // =========================================================================
    // EXTRACTION DU LIEN VIDZY (multi-sous-titres)
    // =========================================================================

    /**
     * Extrait les infos de téléchargement depuis une page vidzy.cc/vidzy.live.
     * Supporte maintenant plusieurs fichiers de sous-titres.
     * Retourne:
     *   - videoUrl        : lien direct MP4
     *   - subtitleUrl     : premier sous-titre (compatibilité)
     *   - subtitles       : JSON array [{url, label, lang}, ...]
     */
    public Map<String, String> extractDownloadInfo(String vidzyUrl) {
        log.info("Extraction lien direct depuis: {}", vidzyUrl);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("videoUrl", vidzyUrl);
        result.put("subtitleUrl", "");
        result.put("subtitles", "[]");

        try {
            String domain = "https://vidzy.live";
            Pattern pDomain = Pattern.compile("(https?://[^/]+)");
            Matcher mDomain = pDomain.matcher(vidzyUrl);
            if (mDomain.find()) domain = mDomain.group(1);

            // Normaliser vidzy.cc -> vidzy.live
            domain = domain.replace("vidzy.cc", "vidzy.live");

            String videoId = extractVidzyId(vidzyUrl);
            if (videoId.isEmpty()) {
                log.warn("Impossible d'extraire le video_id de {}", vidzyUrl);
                return result;
            }

            String embedUrl = domain + "/embed-" + videoId + ".html";
            String downloadUrl = domain + "/d/" + videoId + "_n";

            // Charger l'embed pour obtenir sous-titres et JS décompressé
            log.info("Chargement embed: {}", embedUrl);
            Document embedDoc = connect(embedUrl)
                    .referrer(baseUrl).ignoreHttpErrors(true).get();
            String embedHtml = embedDoc.html();

            // Décompresser JS Dean Edwards si nécessaire
            String searchSource = tryUnpack(embedHtml);

            // --- Extraction multi-sous-titres ---
            List<Map<String, String>> subtitleList = extractAllSubtitles(searchSource, embedDoc);
            ObjectMapper mapper = new ObjectMapper();
            result.put("subtitles", mapper.writeValueAsString(subtitleList));

            // Premier sous-titre en compatibilité
            if (!subtitleList.isEmpty()) {
                result.put("subtitleUrl", subtitleList.get(0).get("url"));
            }

            // Charger la page de téléchargement
            log.info("Chargement page download: {}", downloadUrl);
            Document downloadDoc = connect(downloadUrl)
                    .referrer(embedUrl).ignoreHttpErrors(true).get();

            String op = "download_orig", id = videoId, mode = "n", hash = "";
            Element form = downloadDoc.selectFirst("form#F1, form[action]");
            if (form != null) {
                Element opEl = form.selectFirst("input[name=op]"); if (opEl != null) op = opEl.attr("value");
                Element idEl = form.selectFirst("input[name=id]"); if (idEl != null) id = idEl.attr("value");
                Element modeEl = form.selectFirst("input[name=mode]"); if (modeEl != null) mode = modeEl.attr("value");
                Element hashEl = form.selectFirst("input[name=hash]"); if (hashEl != null) hash = hashEl.attr("value");
            } else {
                Pattern pHash = Pattern.compile("name=['\"]hash['\"]\\s+value=['\"]([^'\"]+)['\"]");
                Matcher mHash = pHash.matcher(downloadDoc.html());
                if (mHash.find()) hash = mHash.group(1);
            }

            if (!hash.isEmpty()) {
                log.info("Hash trouvé: '{}'. Envoi POST...", hash);
                Connection.Response postResp = connect(downloadUrl)
                        .method(Connection.Method.POST)
                        .data("op", op).data("id", id).data("mode", mode).data("hash", hash)
                        .referrer(downloadUrl).followRedirects(false).ignoreHttpErrors(true).execute();

                if (postResp.statusCode() == 302 || postResp.statusCode() == 301) {
                    String directUrl = postResp.header("Location");
                    if (directUrl != null && !directUrl.isEmpty()) {
                        log.info("Lien MP4 résolu (302): {}", directUrl);
                        result.put("videoUrl", directUrl);
                    }
                } else if (postResp.statusCode() == 200) {
                    String directUrl = extractVideoFromHtml(postResp.body());
                    if (!directUrl.isEmpty()) {
                        log.info("Lien MP4 extrait (200): {}", directUrl);
                        result.put("videoUrl", directUrl);
                    }
                }
            } else {
                log.warn("Hash non trouvé sur la page {}", downloadUrl);
            }

        } catch (IOException e) {
            log.error("Erreur extractDownloadInfo: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Extrait TOUS les sous-titres d'une page embed vidzy.
     * Retourne une liste de {url, label, lang}.
     */
    private List<Map<String, String>> extractAllSubtitles(String source, Document doc) {
        List<Map<String, String>> subs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Patterns pour les sous-titres inline JS: tracks: [{file:"url",label:"Français",kind:"captions"}]
        Pattern tracksPattern = Pattern.compile(
            "tracks\\s*[=:]\\s*\\[([^\\]]+)\\]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher tracksMatcher = tracksPattern.matcher(source);
        while (tracksMatcher.find()) {
            String block = tracksMatcher.group(1);
            Pattern entryPat = Pattern.compile(
                "file\\s*:\\s*['\"]([^'\"]+)['\"](?:.*?label\\s*:\\s*['\"]([^'\"]*)['\"])?",
                Pattern.DOTALL);
            Matcher em = entryPat.matcher(block);
            while (em.find()) {
                String url = em.group(1);
                String label = em.group(2) != null ? em.group(2) : "Sous-titres";
                if (url.matches(".*\\.(vtt|srt|ass)(\\?.*)?$") && !seen.contains(url)) {
                    seen.add(url);
                    subs.add(Map.of("url", url, "label", label, "lang",
                        label.toLowerCase().contains("fr") ? "fr" : "und"));
                }
            }
        }

        // Patterns URL directes vtt/srt
        if (subs.isEmpty()) {
            Pattern vttPat = Pattern.compile(
                "(https?://[^'\"\\s]+\\.(?:vtt|srt)(?:[^'\"\\s]*)?)",
                Pattern.CASE_INSENSITIVE);
            Matcher vm = vttPat.matcher(source);
            int idx = 1;
            while (vm.find()) {
                String url = vm.group(1);
                if (!seen.contains(url)) {
                    seen.add(url);
                    String lang = url.contains("fre") || url.contains("fr") ? "fr" : "und";
                    subs.add(Map.of("url", url, "label", "Sous-titres " + idx++, "lang", lang));
                }
            }
        }

        // Balises <track> HTML5
        Elements htmlTracks = doc.select("track[src]");
        for (Element track : htmlTracks) {
            String kind = track.attr("kind").toLowerCase();
            if (kind.contains("subtitle") || kind.contains("caption") || kind.isEmpty()) {
                String url = track.absUrl("src");
                if (!url.isEmpty() && !seen.contains(url)) {
                    seen.add(url);
                    String label = track.attr("label");
                    if (label.isBlank()) label = track.attr("srclang").isBlank() ? "Sous-titres" : track.attr("srclang");
                    subs.add(Map.of("url", url, "label", label, "lang", track.attr("srclang")));
                }
            }
        }

        log.info("{} sous-titre(s) trouvé(s)", subs.size());
        return subs;
    }

    private String extractVidzyId(String vidzyUrl) {
        if (vidzyUrl.contains("embed-")) {
            int start = vidzyUrl.indexOf("embed-") + 6;
            int end = vidzyUrl.indexOf(".html", start);
            return end != -1 ? vidzyUrl.substring(start, end) : vidzyUrl.substring(start);
        }
        int lastSlash = vidzyUrl.lastIndexOf('/');
        if (lastSlash != -1) {
            return vidzyUrl.substring(lastSlash + 1)
                    .replace("_n", "").replace("_o", "").replace(".html", "");
        }
        return "";
    }

    private String tryUnpack(String html) {
        Pattern pPacker = Pattern.compile(
            "eval\\s*\\(\\s*function\\s*\\(p,\\s*a,\\s*c,\\s*k,\\s*e,\\s*d\\s*\\).*?\\}\\s*\\(\\s*['\"]" +
            "(.*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"]([^'\"]*)['\"]\\.split\\s*\\(\\s*['\"]\\|['\"]\\s*\\)\\s*\\)\\s*\\)");
        Matcher m = pPacker.matcher(html);
        if (m.find()) {
            try {
                return unpack(m.group(1), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(4).split("\\|"));
            } catch (Exception e) {
                log.debug("Unpacker failed: {}", e.getMessage());
            }
        }
        return html;
    }

    private String extractVideoFromHtml(String html) {
        try {
            Document postDoc = Jsoup.parse(html);
            Elements links = postDoc.select("a[href*=.mp4], a[href*=.mkv], a[href*=.avi]");
            if (!links.isEmpty()) return links.first().attr("href");
            Pattern pDirect = Pattern.compile("href=['\"]([^'\"]+\\.(?:mp4|mkv|avi)[^'\"]*)['\"]",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = pDirect.matcher(html);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    public String extractDirectDownloadUrl(String vidzyUrl) {
        return extractDownloadInfo(vidzyUrl).get("videoUrl");
    }

    // =========================================================================
    // UTILITAIRES
    // =========================================================================

    private void parseMetadata(Document doc, AnimeDetail detail) {
        Elements allText = doc.select(".full-story li, .fstory li, .movie-info li, .finfo li, .full-story span, .fstory span");
        for (Element el : allText) {
            String text = el.text();
            if (text.contains("Titre Original")) detail.setOriginalTitle(text.replaceAll(".*:", "").trim());
            if (text.contains("Studio")) detail.setStudio(text.replaceAll(".*:", "").trim());
        }

        List<String> genres = new ArrayList<>();
        for (Element g : doc.select("a[href*=manga_genre]")) {
            String genre = g.text().trim();
            if (!genre.isBlank() && !genres.contains(genre)) genres.add(genre);
        }
        detail.setGenres(genres);

        Element versionEl = doc.selectFirst("a[href*=version-serie]");
        if (versionEl != null) detail.setVersion(versionEl.text().trim());

        List<String> directors = new ArrayList<>();
        for (Element d : doc.select("a[href*=director]")) {
            String name = d.text().trim();
            if (!name.isBlank() && !directors.contains(name)) directors.add(name);
        }
        detail.setDirectors(directors);

        List<String> actors = new ArrayList<>();
        for (Element a : doc.select("a[href*=actors]")) {
            String name = a.text().trim();
            if (!name.isBlank() && !actors.contains(name)) actors.add(name);
        }
        detail.setActors(actors);
    }

    private Document fetchDocument(String url) throws IOException {
        return connect(url)
                .referrer(baseUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .followRedirects(true).get();
    }

    // Décompresseur Dean Edwards p,a,c,k,e,d
    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static String baseN(int num, int radix) {
        if (num == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (num > 0) { sb.append(DIGITS.charAt(num % radix)); num /= radix; }
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
