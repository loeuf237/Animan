package com.pauldev.animan.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.pauldev.animan.model.AnimeDetail;
import com.pauldev.animan.model.AnimeResult;
import com.pauldev.animan.model.Episode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service de scraping pour voir-anime.to avec support anti-bot via Playwright.
 *
 * PRÉREQUIS : lancer une fois au démarrage "playwright install chromium"
 * via la classe PlaywrightInstaller, ou manuellement :
 *   mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
 *
 * Gère deux types de lecteurs vidéo:
 *  - myTV   : vidéos découpées en plusieurs morceaux, plusieurs qualités
 *  - Stape  : fichier unique
 */
@Slf4j
@Service
public class VoirAnimeScrapingService {

    @Value("${animan.voir-anime-url}")
    private String baseUrl;

    @Value("${animan.user-agent}")
    private String userAgent;

    private Playwright playwright;
    private Browser browser;
    private boolean playwrightAvailable = false;

    @org.springframework.beans.factory.annotation.Autowired
    private ProxyRotatorService proxyRotator;

    private final List<AnimeResult> latestCache = new CopyOnWriteArrayList<>();
    private long lastCacheTime = 0;
    private boolean isRefreshing = false;

    // -------------------------------------------------------------------------
    // Initialisation et installation du driver Chromium
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        log.info("Initialisation de VoirAnimeScrapingService...");
        // Tout est séquentiel dans un thread d'arrière-plan :
        //  1. Tenter de lancer Chromium directement (si déjà installé)
        //  2. Si échec → lancer l'installateur dans un sous-processus (évite System.exit())
        //  3. Retenter le lancement de Chromium
        //  4. Pré-charger le cache des dernières sorties
        CompletableFuture.runAsync(() -> {
            // Étape 1 : Tenter directement (Chromium peut déjà être installé)
            boolean chromiumReady = tryLaunchBrowser();
            if (!chromiumReady) {
                log.info("Chromium non disponible, lancement de l'installation des drivers Playwright...");
                boolean installed = runPlaywrightInstaller();
                if (installed) {
                    // Étape 3 : Re-tenter le lancement après installation
                    chromiumReady = tryLaunchBrowser();
                }
            }

            if (chromiumReady) {
                log.info("Pré-chargement asynchrone du cache des dernières sorties (voir-anime)...");
                getLatestReleases();
            } else {
                log.warn("Playwright Chromium indisponible - les fonctionnalités voir-anime seront désactivées.");
            }
        });
    }

    /**
     * Tente de lancer Chromium. Retourne true si le navigateur est opérationnel.
     */
    private boolean tryLaunchBrowser() {
        try {
            getBrowser();
            return true;
        } catch (Exception e) {
            log.debug("Lancement Chromium échoué (drivers peut-être absents): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lance l'installation de Chromium dans un sous-processus séparé
     * pour éviter que System.exit() de Playwright CLI ne termine la JVM.
     * Retourne true si l'installation a réussi (exit code 0).
     */
    private boolean runPlaywrightInstaller() {
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                "com.microsoft.playwright.CLI",
                "install",
                "chromium"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Lire la sortie pour éviter le blocage du buffer
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[playwright-install] {}", line);
                }
            }

            int exitCode = p.waitFor();
            if (exitCode == 0) {
                log.info("Drivers Playwright (Chromium) installés avec succès.");
                return true;
            } else {
                log.warn("L'installation des drivers Playwright a renvoyé le code : {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.warn("L'installation automatique des drivers Playwright a échoué : {}. " +
                     "Lancez manuellement : mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI " +
                     "-D exec.args=\"install chromium\"", e.getMessage());
            return false;
        }
    }

    public List<AnimeResult> getLatestReleases() {
        if (latestCache.isEmpty()) {
            synchronized (latestCache) {
                if (latestCache.isEmpty()) {
                    log.info("Initialisation synchrone du cache des dernières sorties (voir-anime)...");
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
            String html = fetchPageHtml(baseUrl);
            if (!html.isEmpty()) {
                Document doc = Jsoup.parse(html, baseUrl);
                Elements cards = doc.select(".film_list-wrap .flw-item, .flw-item");
                List<AnimeResult> results = new ArrayList<>();
                for (Element card : cards) {
                    try {
                        AnimeResult r = parseCard(card);
                        if (r != null) results.add(r);
                    } catch (Exception e) {
                        log.debug("Erreur parsing card: {}", e.getMessage());
                    }
                }
                if (!results.isEmpty()) {
                    latestCache.clear();
                    latestCache.addAll(results);
                    lastCacheTime = System.currentTimeMillis();
                    log.info("Cache des dernières sorties mis à jour (voir-anime: {} animés)", latestCache.size());
                }
            }
        } catch (Exception e) {
            log.error("Erreur lors de la mise à jour synchrone du cache (voir-anime): {}", e.getMessage());
        }
    }

    private synchronized void triggerBackgroundRefresh() {
        if (isRefreshing) return;
        isRefreshing = true;
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Mise à jour asynchrone du cache des dernières sorties (voir-anime)...");
                String html = fetchPageHtml(baseUrl);
                if (!html.isEmpty()) {
                    Document doc = Jsoup.parse(html, baseUrl);
                    Elements cards = doc.select(".film_list-wrap .flw-item, .flw-item");
                    List<AnimeResult> results = new ArrayList<>();
                    for (Element card : cards) {
                        try {
                            AnimeResult r = parseCard(card);
                            if (r != null) results.add(r);
                        } catch (Exception e) {
                            log.debug("Erreur parsing card: {}", e.getMessage());
                        }
                    }
                    if (!results.isEmpty()) {
                        latestCache.clear();
                        latestCache.addAll(results);
                        lastCacheTime = System.currentTimeMillis();
                        log.info("Mise à jour asynchrone du cache réussie (voir-anime: {} animés)", latestCache.size());
                    }
                }
            } catch (Exception e) {
                log.error("Erreur lors de la mise à jour asynchrone du cache (voir-anime): {}", e.getMessage());
            } finally {
                isRefreshing = false;
            }
        });
    }

    private synchronized Browser getBrowser() {
        if (browser != null && browser.isConnected()) return browser;

        log.info("Lancement de Playwright Chromium...");
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of(
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-blink-features=AutomationControlled",
                        "--disable-dev-shm-usage"
                    ))
            );
            playwrightAvailable = true;
            log.info("Playwright Chromium lancé avec succès.");
        } catch (Exception e) {
            playwrightAvailable = false;
            log.error("Impossible de lancer Playwright Chromium: {}.\n" +
                      "==> Solution: exécutez dans le répertoire du projet:\n" +
                      "    mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI " +
                      "-D exec.args=\"install chromium\"\n" +
                      "    ou via IntelliJ: Run > Edit Configurations > Add Maven Goal", e.getMessage());
            throw new RuntimeException("Playwright Chromium non disponible. " +
                "Installez les drivers: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI " +
                "-Dexec.args=\"install chromium\"", e);
        }
        return browser;
    }

    @PreDestroy
    public void closeBrowser() {
        try { if (browser != null) browser.close(); } catch (Exception ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
    }

    private String fetchPageHtml(String url) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
            .setUserAgent(proxyRotator != null ? proxyRotator.getUserAgent() : userAgent)
            .setExtraHTTPHeaders(Map.of(
                "Accept-Language", "fr-FR,fr;q=0.9",
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ));
        
        java.net.Proxy netProxy = proxyRotator != null ? proxyRotator.getRandomProxy() : null;
        if (netProxy != null && netProxy.address() instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress addr = (java.net.InetSocketAddress) netProxy.address();
            options.setProxy(new com.microsoft.playwright.options.Proxy("http://" + addr.getHostString() + ":" + addr.getPort()));
        }

        try (BrowserContext ctx = getBrowser().newContext(options)) {
            Page page = ctx.newPage();
            page.addInitScript(
                "Object.defineProperty(navigator,'webdriver',{get:()=>undefined});"
            );
            page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE)
                .setTimeout(30000));

            String title = page.title();
            if (title.contains("Just a moment") || title.contains("Cloudflare")) {
                log.info("Challenge Cloudflare détecté sur {}, attente 8s...", url);
                page.waitForTimeout(8000);
            }
            return page.content();
        } catch (Exception e) {
            log.error("[voir-anime] fetchPageHtml error pour {}: {}", url, e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------

    public List<AnimeResult> searchAnime(String query) {
        log.info("[voir-anime] Recherche: '{}'", query);
        List<AnimeResult> results = new ArrayList<>();
        if (!playwrightAvailable) {
            try { getBrowser(); } catch (Exception e) {
                log.warn("[voir-anime] Playwright indisponible pour la recherche. " +
                         "Installez les drivers Chromium.");
                return results;
            }
        }
        try {
            String searchUrl = baseUrl + "/?s=" + query.replace(" ", "+");
            String html = fetchPageHtml(searchUrl);
            if (html.isEmpty()) return results;

            Document doc = Jsoup.parse(html, baseUrl);
            Elements cards = doc.select(".film_list-wrap .flw-item, .flw-item");
            for (Element card : cards) {
                try {
                    AnimeResult r = parseCard(card);
                    if (r != null) results.add(r);
                } catch (Exception e) {
                    log.debug("Erreur parsing card: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[voir-anime] Erreur recherche: {}", e.getMessage());
        }
        log.info("[voir-anime] {} résultat(s) pour '{}'", results.size(), query);
        return results;
    }

    private AnimeResult parseCard(Element card) {
        Element linkEl = card.selectFirst("a.film-poster-ahref, a[href*='/regarder/'], a[href*='/anime/']");
        if (linkEl == null) linkEl = card.selectFirst("h3 a, h2 a, .film-name a");
        if (linkEl == null) return null;

        String url = linkEl.absUrl("href");
        if (url.isBlank()) url = baseUrl + linkEl.attr("href");

        Element titleEl = card.selectFirst(".film-name, h3, h2, .dynamic-name");
        String title = titleEl != null ? titleEl.text().trim() : linkEl.attr("title").trim();
        if (title.isBlank()) return null;

        Element img = card.selectFirst("img[src], img[data-src]");
        String imageUrl = "";
        if (img != null) {
            imageUrl = img.absUrl("src");
            if (imageUrl.isBlank()) imageUrl = img.absUrl("data-src");
        }

        Element epEl = card.selectFirst(".film-poster-quality, .tick-eps, .tick-dub");
        String epInfo = epEl != null ? epEl.text().trim() : "";

        return AnimeResult.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .title(title).imageUrl(imageUrl).url(url)
                .version("VOSTFR").type("Anime").episodeInfo(epInfo)
                .build();
    }

    // -------------------------------------------------------------------------
    // Détail + épisodes
    // -------------------------------------------------------------------------

    public AnimeDetail getAnimeDetail(String animeUrl) {
        log.info("[voir-anime] Détail: {}", animeUrl);
        try {
            String html = fetchPageHtml(animeUrl);
            if (html.isEmpty()) return null;

            Document doc = Jsoup.parse(html, baseUrl);
            AnimeDetail detail = new AnimeDetail();
            detail.setUrl(animeUrl);

            Element titleEl = doc.selectFirst("h2.film-name, h1.film-name, .dp-i-content h2, h1");
            detail.setTitle(titleEl != null ? titleEl.text().trim()
                    : doc.title().replaceAll("\\|.*", "").trim());

            Element img = doc.selectFirst(".film-poster img, img.film-poster");
            if (img != null)
                detail.setImageUrl(img.absUrl("src").isBlank() ? img.absUrl("data-src") : img.absUrl("src"));

            Element synEl = doc.selectFirst(".film-description .text, .dp-d-desc, .fsd-item .text");
            if (synEl != null) detail.setSynopsis(synEl.text().trim());

            List<String> genres = new ArrayList<>();
            doc.select(".item-list a[href*=genre]").forEach(g -> genres.add(g.text().trim()));
            detail.setGenres(genres);

            List<Episode> episodes = fetchEpisodes(animeUrl, doc);
            detail.setEpisodes(episodes);

            log.info("[voir-anime] '{}' - {} épisode(s)", detail.getTitle(), episodes.size());
            return detail;
        } catch (Exception e) {
            log.error("[voir-anime] Erreur détail: {}", e.getMessage());
            return null;
        }
    }

    private List<Episode> fetchEpisodes(String animeUrl, Document doc) {
        List<Episode> episodes = new ArrayList<>();
        try {
            String animeId = extractAnimeId(animeUrl, doc);
            if (!animeId.isEmpty()) {
                String apiUrl = baseUrl + "/ajax/v2/episode/list/" + animeId;
                String apiHtml = fetchPageHtml(apiUrl);
                if (!apiHtml.isEmpty()) {
                    Document apiDoc = Jsoup.parse(apiHtml);
                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode root = mapper.readTree(apiHtml);
                        if (root.has("html")) apiDoc = Jsoup.parse(root.get("html").asText());
                    } catch (Exception ignored) {}

                    Elements epLinks = apiDoc.select("a[href*=regarder], a[data-id]");
                    int epNum = 1;
                    for (Element epLink : epLinks) {
                        String epId = epLink.attr("data-id");
                        String epTitle = epLink.text().trim();
                        if (epTitle.isBlank()) epTitle = "Épisode " + epNum;

                        String dataNum = epLink.attr("data-number");
                        try { epNum = Integer.parseInt(dataNum.isBlank() ? extractNumber(epTitle, epNum) + "" : dataNum); }
                        catch (Exception ignored) {}

                        String playerUrl = epLink.absUrl("href");
                        if (playerUrl.isBlank()) playerUrl = baseUrl + "/regarder/" + epId;

                        episodes.add(Episode.builder()
                                .number(epNum).title(epTitle)
                                .playerUrl(playerUrl).downloadPageUrl(playerUrl)
                                .version("VOSTFR").build());
                        epNum++;
                    }
                }
            }

            if (episodes.isEmpty()) {
                Elements epItems = doc.select(".ep-item, .ssl-item.ep-item, a[href*=regarder]");
                int num = 1;
                for (Element ep : epItems) {
                    String href = ep.absUrl("href");
                    String text = ep.text().trim();
                    episodes.add(Episode.builder()
                            .number(num).title(text.isBlank() ? "Épisode " + num : text)
                            .playerUrl(href).downloadPageUrl(href)
                            .version("VOSTFR").build());
                    num++;
                }
            }
        } catch (Exception e) {
            log.error("[voir-anime] fetchEpisodes: {}", e.getMessage());
        }
        episodes.sort(Comparator.comparingInt(Episode::getNumber));
        return episodes;
    }

    private String extractAnimeId(String url, Document doc) {
        Matcher m = Pattern.compile("/(\\d+)-").matcher(url);
        if (m.find()) return m.group(1);
        Element el = doc.selectFirst("[data-id]");
        return el != null ? el.attr("data-id") : "";
    }

    private int extractNumber(String text, int fallback) {
        Matcher m = Pattern.compile("\\d+").matcher(text);
        return m.find() ? Integer.parseInt(m.group()) : fallback;
    }

    // -------------------------------------------------------------------------
    // Extraction lien de téléchargement (myTV + Stape)
    // -------------------------------------------------------------------------

    public Map<String, String> extractDownloadInfo(String episodeUrl) {
        log.info("[voir-anime] extractDownloadInfo: {}", episodeUrl);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("videoUrl", ""); result.put("videoChunks", "[]");
        result.put("videoQualities", "{}"); result.put("subtitleUrl", "");
        result.put("serverType", "unknown");

        Browser.NewContextOptions options = new Browser.NewContextOptions()
            .setUserAgent(proxyRotator != null ? proxyRotator.getUserAgent() : userAgent);
        
        java.net.Proxy netProxy = proxyRotator != null ? proxyRotator.getRandomProxy() : null;
        if (netProxy != null && netProxy.address() instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress addr = (java.net.InetSocketAddress) netProxy.address();
            options.setProxy(new com.microsoft.playwright.options.Proxy("http://" + addr.getHostString() + ":" + addr.getPort()));
        }

        try (BrowserContext ctx = getBrowser().newContext(options)) {
            Page page = ctx.newPage();
            page.addInitScript("Object.defineProperty(navigator,'webdriver',{get:()=>undefined});");

            List<String> capturedVideoUrls = new ArrayList<>();
            page.onRequest(req -> {
                String reqUrl = req.url();
                if (reqUrl.matches(".*\\.(mp4|m3u8|ts)(\\?.*)?$") ||
                    reqUrl.contains("cdn") && reqUrl.contains("video"))
                    capturedVideoUrls.add(reqUrl);
            });

            page.navigate(episodeUrl, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(30000));

            String html = page.content();
            Document doc = Jsoup.parse(html, baseUrl);
            String serverType = detectServerType(doc, html);
            result.put("serverType", serverType);

            if ("myTV".equals(serverType))       extractMyTvInfo(page, doc, html, result, capturedVideoUrls);
            else if ("stape".equals(serverType)) extractStapeInfo(page, doc, html, result, capturedVideoUrls);
            else if (!capturedVideoUrls.isEmpty()) result.put("videoUrl", capturedVideoUrls.get(0));

        } catch (Exception e) {
            log.error("[voir-anime] extractDownloadInfo: {}", e.getMessage());
        }
        return result;
    }

    private String detectServerType(Document doc, String html) {
        if (html.contains("mytv") || html.contains("myTV") ||
            !doc.select("iframe[src*=mytv], iframe[src*=myfilm]").isEmpty()) return "myTV";
        if (html.contains("stape") || html.contains("stapro") ||
            !doc.select("iframe[src*=stape], iframe[src*=stapro]").isEmpty()) return "stape";
        for (Element btn : doc.select(".serversList li, .server-item, [data-server]")) {
            String text = btn.text().toLowerCase();
            if (text.contains("mytv")) return "myTV";
            if (text.contains("stape")) return "stape";
        }
        return "unknown";
    }

    private void extractMyTvInfo(Page page, Document doc, String html,
                                  Map<String, String> result, List<String> capturedUrls) {
        try {
            Element iframe = doc.selectFirst("iframe[src*=mytv], iframe[src*=myfilm], iframe[src*=moon]");
            if (iframe != null) {
                page.navigate(iframe.absUrl("src"), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(20000));
                html = page.content();
            }

            Pattern sourcesPattern = Pattern.compile("sources\\s*[=:]\\s*\\[([^\\]]+)\\]", Pattern.DOTALL);
            Matcher m = sourcesPattern.matcher(html);
            Map<String, String> qualities = new LinkedHashMap<>();
            List<String> chunks = new ArrayList<>();
            String bestUrl = "";

            if (m.find()) {
                String block = m.group(1);
                Matcher em = Pattern.compile(
                    "file\\s*:\\s*['\"]([^'\"]+)['\"].*?label\\s*:\\s*['\"]([^'\"]*)['\"]", Pattern.DOTALL)
                    .matcher(block);
                while (em.find()) {
                    String fileUrl = em.group(1), label = em.group(2).trim();
                    if (!fileUrl.isBlank()) { qualities.put(label.isEmpty() ? "default" : label, fileUrl); if (bestUrl.isEmpty()) bestUrl = fileUrl; }
                }
                if (qualities.isEmpty()) {
                    Matcher um = Pattern.compile("['\"]([^'\"]+\\.(?:mp4|m3u8)[^'\"]*)['\"]").matcher(block);
                    while (um.find()) { String u = um.group(1); if (!chunks.contains(u)) chunks.add(u); }
                    if (!chunks.isEmpty()) bestUrl = chunks.get(0);
                }
            }

            if (bestUrl.isEmpty() && !capturedUrls.isEmpty()) {
                for (String u : capturedUrls) {
                    if (u.contains("1080")) qualities.put("1080p", u);
                    else if (u.contains("720")) qualities.put("720p", u);
                    else if (u.contains("480")) qualities.put("480p", u);
                    else chunks.add(u);
                }
                if (!qualities.isEmpty()) bestUrl = qualities.values().iterator().next();
                else if (!chunks.isEmpty()) bestUrl = chunks.get(0);
            }

            ObjectMapper mapper = new ObjectMapper();
            result.put("videoUrl", bestUrl);
            result.put("videoQualities", mapper.writeValueAsString(qualities));
            result.put("videoChunks", mapper.writeValueAsString(chunks.isEmpty() ? new ArrayList<>(qualities.values()) : chunks));
            result.put("subtitleUrl", extractSubtitle(html));
            log.info("[myTV] {} qualité(s), {} morceau(x)", qualities.size(), chunks.size());
        } catch (Exception e) { log.error("[myTV] {}", e.getMessage()); }
    }

    private void extractStapeInfo(Page page, Document doc, String html,
                                   Map<String, String> result, List<String> capturedUrls) {
        try {
            Element iframe = doc.selectFirst("iframe[src*=stape], iframe[src*=stapro]");
            if (iframe != null) {
                page.navigate(iframe.absUrl("src"), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(20000));
                html = page.content();
            }

            String videoUrl = "";
            Matcher m = Pattern.compile("['\"]([^'\"]+\\.(?:mp4|m3u8)[^'\"]*)['\"]", Pattern.CASE_INSENSITIVE).matcher(html);
            while (m.find()) {
                String candidate = m.group(1);
                if (candidate.startsWith("http") && !candidate.contains("poster")) { videoUrl = candidate; break; }
            }
            if (videoUrl.isEmpty() && !capturedUrls.isEmpty()) videoUrl = capturedUrls.get(0);

            result.put("videoUrl", videoUrl);
            result.put("videoChunks", "[\"" + videoUrl + "\"]");
            result.put("subtitleUrl", extractSubtitle(html));
            log.info("[Stape] URL: {}", videoUrl);
        } catch (Exception e) { log.error("[Stape] {}", e.getMessage()); }
    }

    private String extractSubtitle(String html) {
        Matcher m = Pattern.compile("['\"]([^'\"]+\\.(?:vtt|srt)[^'\"]*)['\"]", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) { String s = m.group(1); if (s.startsWith("http")) return s; }
        return "";
    }

    public List<String> extractVideoChunks(String episodeUrl) {
        Map<String, String> info = extractDownloadInfo(episodeUrl);
        try {
            List<String> chunks = new ArrayList<>();
            for (JsonNode node : new ObjectMapper().readTree(info.get("videoChunks"))) chunks.add(node.asText());
            return chunks;
        } catch (Exception e) { return List.of(); }
    }

    public Map<String, String> extractVideoQualities(String episodeUrl) {
        Map<String, String> info = extractDownloadInfo(episodeUrl);
        try {
            Map<String, String> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = new ObjectMapper().readTree(info.get("videoQualities")).fields();
            while (fields.hasNext()) { Map.Entry<String, JsonNode> e = fields.next(); result.put(e.getKey(), e.getValue().asText()); }
            return result;
        } catch (Exception e) { return Map.of(); }
    }
}
