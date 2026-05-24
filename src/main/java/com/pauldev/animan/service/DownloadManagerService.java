package com.pauldev.animan.service;

import com.pauldev.animan.model.DownloadTask;
import com.pauldev.animan.model.DownloadTask.DownloadStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Gestionnaire de téléchargements ultra-robuste avec support de :
 * - Reprise sur coupure (HTTP Range)
 * - Retentatives automatiques (Auto-retry exponentiel)
 * - Limitation de bande passante (Throttling)
 * - Planification
 * - Muxing FFmpeg automatique
 * - SSE pour progression temps réel.
 */
@Slf4j
@Service
public class DownloadManagerService {

    private final Executor downloadExecutor;
    private final ScrapingService scrapingService;

    @Value("${animan.download-dir}")
    private String downloadDir;

    @Value("${animan.user-agent}")
    private String userAgent;

    @Value("${animan.connection-timeout}")
    private int connectionTimeout;

    @Value("${animan.read-timeout}")
    private int readTimeout;

    @Value("${animan.max-concurrent-downloads}")
    private int maxConcurrentDownloads = 3;

    /** Toutes les tâches de téléchargement indexées par ID. */
    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();

    /** Liste ordonnée des IDs pour l'affichage. */
    private final List<String> taskOrder = new CopyOnWriteArrayList<>();

    /** Clients SSE connectés pour recevoir les mises à jour. */
    private final List<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    /** Limite de vitesse globale (octets/sec). 0 = Illimité. */
    private volatile long globalSpeedLimit = 0;

    /** Cache pour stocker les informations de téléchargement pré-résolues. */
    public static class ResolvedVideoInfo {
        public String directUrl;
        public String subtitleUrl;
        public long size;
        public String formattedSize;
    }

    private final Map<String, ResolvedVideoInfo> resolvedInfoCache = new ConcurrentHashMap<>();

    public DownloadManagerService(@Qualifier("downloadExecutor") Executor downloadExecutor,
                                   ScrapingService scrapingService) {
        this.downloadExecutor = downloadExecutor;
        this.scrapingService = scrapingService;
    }

    /**
     * Définit la limite globale de débit et l'applique aux tâches en cours.
     */
    public void setGlobalSpeedLimit(long limitBytesPerSec) {
        this.globalSpeedLimit = limitBytesPerSec;
        for (DownloadTask task : tasks.values()) {
            if (task.getStatus() == DownloadStatus.DOWNLOADING || task.getStatus() == DownloadStatus.PENDING) {
                task.setMaxSpeedLimit(limitBytesPerSec);
            }
        }
        log.info("Limite de débit globale définie à : {} octets/s", limitBytesPerSec);
    }

    public long getGlobalSpeedLimit() {
        return this.globalSpeedLimit;
    }

    public int getMaxConcurrentDownloads() {
        return this.maxConcurrentDownloads;
    }

    public void setMaxConcurrentDownloads(int limit) {
        this.maxConcurrentDownloads = limit;
        log.info("Limite de téléchargements simultanés mise à jour à : {}", limit);
        processQueue();
    }

    /**
     * Définit la limite de vitesse individuelle d'une tâche.
     */
    public boolean setTaskSpeedLimit(String taskId, long limitBytesPerSec) {
        DownloadTask task = tasks.get(taskId);
        if (task != null) {
            task.setMaxSpeedLimit(limitBytesPerSec);
            broadcastUpdate(task);
            log.info("Limite de débit pour la tâche {} définie à : {} octets/s", taskId, limitBytesPerSec);
            return true;
        }
        return false;
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex]);
    }

    /**
     * Résout asynchroniquement la taille et les liens d'un épisode et met en cache le résultat.
     */
    public ResolvedVideoInfo getOrResolveEpisodeInfo(String downloadPageUrl) {
        if (downloadPageUrl == null || downloadPageUrl.isBlank()) return null;
        if (resolvedInfoCache.containsKey(downloadPageUrl)) {
            return resolvedInfoCache.get(downloadPageUrl);
        }

        try {
            log.info("Résolution asynchrone pour la page : {}", downloadPageUrl);
            Map<String, String> downloadInfo = scrapingService.extractDownloadInfo(downloadPageUrl);
            String directUrl = downloadInfo.get("videoUrl");
            String subtitleUrl = downloadInfo.get("subtitleUrl");

            long size = -1;
            if (directUrl != null && !directUrl.isEmpty() && !directUrl.equals(downloadPageUrl)) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(directUrl).openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setRequestProperty("User-Agent", userAgent);
                    conn.setRequestProperty("Referer", "https://vidzy.live/");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    size = conn.getContentLengthLong();
                    conn.disconnect();
                } catch (Exception e) {
                    log.warn("HEAD request for size failed: {}", e.getMessage());
                }
            }

            ResolvedVideoInfo info = new ResolvedVideoInfo();
            info.directUrl = directUrl;
            info.subtitleUrl = subtitleUrl;
            info.size = size;
            info.formattedSize = size > 0 ? formatBytes(size) : "Taille inconnue";

            resolvedInfoCache.put(downloadPageUrl, info);
            return info;
        } catch (Exception e) {
            log.error("Erreur de résolution asynchrone de l'épisode : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Démarre un téléchargement unique avec planification, débit individuel et choix des sous-titres.
     */
    public DownloadTask startDownload(String animeName, int episodeNumber,
                                       String downloadPageUrl, String fileName,
                                       String subtitleUrl, LocalDateTime scheduledStartTime, long speedLimit, boolean downloadSubtitles) {

        long initialTotalBytes = 0;
        ResolvedVideoInfo cachedInfo = resolvedInfoCache.get(downloadPageUrl);
        if (cachedInfo != null && cachedInfo.size > 0) {
            initialTotalBytes = cachedInfo.size;
        }

        DownloadTask task = DownloadTask.builder()
                .id(UUID.randomUUID().toString())
                .animeName(animeName)
                .episodeNumber(episodeNumber)
                .fileName(sanitizeFileName(fileName))
                .fileUrl(downloadPageUrl)
                .downloadPageUrl(downloadPageUrl)
                .subtitleUrl(subtitleUrl)
                .downloadSubtitles(downloadSubtitles)
                .status(scheduledStartTime != null && scheduledStartTime.isAfter(LocalDateTime.now()) ? DownloadStatus.SCHEDULED : DownloadStatus.PENDING)
                .scheduledStartTime(scheduledStartTime)
                .maxSpeedLimit(speedLimit > 0 ? speedLimit : this.globalSpeedLimit)
                .totalBytes(initialTotalBytes)
                .progress(0)
                .build();

        // Créer le sous-dossier pour l'anime
        String animeDirPath = downloadDir + File.separator + sanitizeFileName(animeName);
        File animeDir = new File(animeDirPath);
        if (!animeDir.exists()) animeDir.mkdirs();
        task.setSavePath(animeDirPath + File.separator + task.getFileName());

        tasks.put(task.getId(), task);
        taskOrder.add(task.getId());

        if (task.getStatus() == DownloadStatus.PENDING) {
            processQueue();
        }

        broadcastUpdate(task);
        return task;
    }

    public DownloadTask startDownload(String animeName, int episodeNumber,
                                       String downloadPageUrl, String fileName,
                                       String subtitleUrl, LocalDateTime scheduledStartTime, long speedLimit) {
        return startDownload(animeName, episodeNumber, downloadPageUrl, fileName, subtitleUrl, scheduledStartTime, speedLimit, false);
    }

    /**
     * Surcharges de rétrocompatibilité.
     */
    public DownloadTask startDownload(String animeName, int episodeNumber,
                                       String downloadPageUrl, String fileName,
                                       String subtitleUrl) {
        return startDownload(animeName, episodeNumber, downloadPageUrl, fileName, subtitleUrl, null, 0, false);
    }

    public DownloadTask startDownload(String animeName, int episodeNumber,
                                       String downloadPageUrl, String fileName) {
        return startDownload(animeName, episodeNumber, downloadPageUrl, fileName, null, null, 0, false);
    }

    /**
     * Démarre le téléchargement de plusieurs épisodes.
     */
    public List<DownloadTask> startBatchDownload(String animeName,
                                                  List<Map<String, String>> episodes) {
        List<DownloadTask> startedTasks = new ArrayList<>();

        for (Map<String, String> ep : episodes) {
            int epNum = Integer.parseInt(ep.getOrDefault("number", "0"));
            String url = ep.getOrDefault("url", "");
            String name = ep.getOrDefault("fileName",
                    animeName + " - Episode " + epNum + ".mp4");
            String subtitleUrl = ep.getOrDefault("subtitleUrl", "");
            boolean downloadSubtitles = Boolean.parseBoolean(ep.getOrDefault("downloadSubtitles", "false"));

            // Paramètres avancés de planification et débit
            String scheduledTimeStr = ep.get("scheduledTime");
            LocalDateTime scheduledTime = null;
            if (scheduledTimeStr != null && !scheduledTimeStr.isBlank()) {
                try {
                    scheduledTime = LocalDateTime.parse(scheduledTimeStr);
                } catch (Exception e) {
                    log.warn("Date de planification invalide pour l'épisode {} : {}", epNum, scheduledTimeStr);
                }
            }

            long speedLimit = 0;
            String speedLimitStr = ep.get("speedLimit");
            if (speedLimitStr != null && !speedLimitStr.isBlank()) {
                try {
                    speedLimit = Long.parseLong(speedLimitStr);
                } catch (Exception e) {
                    log.warn("Limite de vitesse invalide pour l'épisode {} : {}", epNum, speedLimitStr);
                }
            }

            if (!url.isBlank()) {
                DownloadTask task = startDownload(animeName, epNum, url, name, subtitleUrl, scheduledTime, speedLimit, downloadSubtitles);
                startedTasks.add(task);
            }
        }

        return startedTasks;
    }

    /**
     * P     * S'exécute toutes les 5 secondes.
     */
    @Scheduled(fixedDelay = 5000)
    public void checkScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        for (DownloadTask task : tasks.values()) {
            if (task.getStatus() == DownloadStatus.SCHEDULED &&
                task.getScheduledStartTime() != null &&
                task.getScheduledStartTime().isBefore(now)) {

                log.info("Démarrage différé planifié pour: {}", task.getFileName());
                task.setStatus(DownloadStatus.PENDING);
                broadcastUpdate(task);
                processQueue();
            }
        }
    }

    /**
     * Annule un téléchargement en cours.
     */
    public boolean cancelDownload(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return false;

        if (task.getStatus() == DownloadStatus.DOWNLOADING ||
            task.getStatus() == DownloadStatus.PENDING ||
            task.getStatus() == DownloadStatus.RETRYING ||
            task.getStatus() == DownloadStatus.SCHEDULED) {
            task.setCancelled(true);
            task.setStatus(DownloadStatus.CANCELLED);
            broadcastUpdate(task);
            log.info("Téléchargement annulé: {}", task.getFileName());
            processQueue();
            return true;
        }
        return false;
    }

    /**
     * Met en pause un téléchargement actif.
     */
    public boolean pauseDownload(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return false;

        if (task.getStatus() == DownloadStatus.DOWNLOADING ||
            task.getStatus() == DownloadStatus.PENDING ||
            task.getStatus() == DownloadStatus.RETRYING ||
            task.getStatus() == DownloadStatus.SCHEDULED) {
            task.setPaused(true);
            task.setStatus(DownloadStatus.PAUSED);
            task.setSpeed(0);
            broadcastUpdate(task);
            log.info("Téléchargement mis en pause: {}", task.getFileName());
            processQueue();
            return true;
        }
        return false;
    }

    /**
     * Reprend un téléchargement en pause, échoué ou annulé.
     */
    public boolean resumeDownload(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) return false;

        if (task.getStatus() == DownloadStatus.PAUSED ||
            task.getStatus() == DownloadStatus.FAILED ||
            task.getStatus() == DownloadStatus.CANCELLED) {
            
            task.setPaused(false);
            task.setCancelled(false);
            task.setStatus(DownloadStatus.PENDING);
            task.setError(null);
            broadcastUpdate(task);
            log.info("Reprise du téléchargement: {}", task.getFileName());
            
            processQueue();
            return true;
        }
        return false;
    }

    /**
     * Supprime une tâche de la liste.
     */
    public boolean removeTask(String taskId) {
        DownloadTask task = tasks.remove(taskId);
        if (task != null) {
            taskOrder.remove(taskId);
            if (task.getStatus() == DownloadStatus.DOWNLOADING || task.getStatus() == DownloadStatus.RETRYING) {
                task.setCancelled(true);
            }
            processQueue();
            return true;
        }
        return false;
    }

    /**
     * Retourne toutes les tâches dans l'ordre de création.
     */
    public List<DownloadTask> getAllTasks() {
        List<DownloadTask> ordered = new ArrayList<>();
        for (String id : taskOrder) {
            DownloadTask t = tasks.get(id);
            if (t != null) ordered.add(t);
        }
        return ordered;
    }

    /**
     * Retourne une tâche par ID.
     */
    public DownloadTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Enregistre un nouveau client SSE.
     */
    public SseEmitter createSseEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        sseEmitters.add(emitter);

        emitter.onCompletion(() -> sseEmitters.remove(emitter));
        emitter.onTimeout(() -> sseEmitters.remove(emitter));
        emitter.onError(e -> sseEmitters.remove(emitter));

        // Envoyer l'état actuel de toutes les tâches
        for (DownloadTask task : getAllTasks()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("download-update")
                        .data(taskToMap(task)));
            } catch (IOException e) {
                sseEmitters.remove(emitter);
            }
        }

        return emitter;
    }

    /**
     * Logique de téléchargement effective avec retentatives, reprise et régulation.
     */
    private void executeDownload(DownloadTask task, String subtitleUrl) {
        try {
            int maxRetries = 3;
            task.setRetryCount(0);
            task.setSubtitleUrl(subtitleUrl);

            while (true) {
                HttpURLConnection connection = null;
                try {
                    if (task.isCancelled()) {
                        task.setStatus(DownloadStatus.CANCELLED);
                        broadcastUpdate(task);
                        return;
                    }
                    if (task.isPaused()) {
                        task.setStatus(DownloadStatus.PAUSED);
                        broadcastUpdate(task);
                        return;
                    }

                    task.setStatus(DownloadStatus.DOWNLOADING);
                    broadcastUpdate(task);

                    // Étape 1: Résoudre le lien direct
                    String directUrl = task.getFileUrl();
                    if (directUrl.contains("vidzy") || directUrl.contains("luluvdo")
                            || !directUrl.matches(".*\\.(mp4|mkv|avi)(\\?.*)?$")) {

                        log.info("Résolution du lien direct pour: {}", directUrl);
                        Map<String, String> downloadInfo = scrapingService.extractDownloadInfo(directUrl);
                        directUrl = downloadInfo.get("videoUrl");
                        task.setFileUrl(directUrl);
                        task.setDirectDownloadUrl(directUrl);

                        // Récupérer les sous-titres si non fournis
                        if ((subtitleUrl == null || subtitleUrl.isBlank()) &&
                                downloadInfo.containsKey("subtitleUrl") && !downloadInfo.get("subtitleUrl").isBlank()) {
                            subtitleUrl = downloadInfo.get("subtitleUrl");
                            task.setSubtitleUrl(subtitleUrl);
                            log.info("Sous-titres trouvés: {}", subtitleUrl);
                        }
                    } else {
                        task.setDirectDownloadUrl(directUrl);
                    }

                    // Détecter si le serveur supporte la reprise (Range HTTP)
                    boolean acceptRanges = false;
                    long totalBytes = -1;
                    try {
                        HttpURLConnection headConn = (HttpURLConnection) new URL(directUrl).openConnection();
                        headConn.setRequestMethod("HEAD");
                        headConn.setRequestProperty("User-Agent", userAgent);
                        headConn.setRequestProperty("Referer", "https://vidzy.live/");
                        headConn.setConnectTimeout(connectionTimeout);
                        headConn.setReadTimeout(readTimeout);
                        int headCode = headConn.getResponseCode();
                        acceptRanges = "bytes".equalsIgnoreCase(headConn.getHeaderField("Accept-Ranges"));
                        totalBytes = headConn.getContentLengthLong();
                        headConn.disconnect();
                    } catch (Exception e) {
                        log.debug("HEAD request failed, testing Range support via GET: {}", e.getMessage());
                    }

                    if (!acceptRanges) {
                        try {
                            HttpURLConnection testConn = (HttpURLConnection) new URL(directUrl).openConnection();
                            testConn.setRequestMethod("GET");
                            testConn.setRequestProperty("User-Agent", userAgent);
                            testConn.setRequestProperty("Referer", "https://vidzy.live/");
                            testConn.setRequestProperty("Range", "bytes=0-0");
                            testConn.setConnectTimeout(connectionTimeout);
                            testConn.setReadTimeout(readTimeout);
                            int testCode = testConn.getResponseCode();
                            acceptRanges = (testCode == 206);
                            if (totalBytes <= 0) {
                                String contentRange = testConn.getHeaderField("Content-Range");
                                if (contentRange != null && contentRange.contains("/")) {
                                    totalBytes = Long.parseLong(contentRange.substring(contentRange.lastIndexOf("/") + 1));
                                }
                            }
                            testConn.disconnect();
                        } catch (Exception e) {
                            log.debug("Range test GET failed: {}", e.getMessage());
                        }
                    }

                    task.setSupportsResume(acceptRanges);
                    if (totalBytes > 0) {
                        task.setTotalBytes(totalBytes);
                        broadcastUpdate(task);
                    }

                    // Étape 2: Ouvrir la connexion HTTP de téléchargement
                    URL url = new URL(directUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("User-Agent", userAgent);
                    connection.setRequestProperty("Referer", "https://vidzy.live/");
                    connection.setConnectTimeout(connectionTimeout);
                    connection.setReadTimeout(readTimeout);
                    connection.setInstanceFollowRedirects(true);

                    File outputFile = new File(task.getSavePath());
                    long existingLength = 0;
                    boolean isResume = false;

                    // Reprise si activée et fichier existant
                    if (acceptRanges && outputFile.exists() && outputFile.length() > 0 && outputFile.length() < totalBytes) {
                        existingLength = outputFile.length();
                        connection.setRequestProperty("Range", "bytes=" + existingLength + "-");
                        isResume = true;
                        log.info("Reprise du téléchargement pour {} à partir de {} octets", task.getFileName(), existingLength);
                    }

                    int responseCode = connection.getResponseCode();

                    // Ré-extraire dynamiquement le lien si le lien précédent a expiré (HTTP 403, 404, 410, 401)
                    if ((responseCode == 403 || responseCode == 404 || responseCode == 410 || responseCode == 401)
                            && task.getDownloadPageUrl() != null && !task.getDownloadPageUrl().isBlank()) {
                        log.info("Lien direct expiré (HTTP {}), tentative de re-résolution depuis la page source : {}", responseCode, task.getDownloadPageUrl());
                        connection.disconnect();

                        Map<String, String> downloadInfo = scrapingService.extractDownloadInfo(task.getDownloadPageUrl());
                        directUrl = downloadInfo.get("videoUrl");
                        task.setFileUrl(directUrl);
                        task.setDirectDownloadUrl(directUrl);

                        url = new URL(directUrl);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("User-Agent", userAgent);
                        connection.setRequestProperty("Referer", "https://vidzy.live/");
                        connection.setConnectTimeout(connectionTimeout);
                        connection.setReadTimeout(readTimeout);
                        connection.setInstanceFollowRedirects(true);
                        if (isResume) {
                            connection.setRequestProperty("Range", "bytes=" + existingLength + "-");
                        }
                        responseCode = connection.getResponseCode();
                    }

                    // Gérer les redirections manuelles
                    if (responseCode == 301 || responseCode == 302) {
                        String redirectUrl = connection.getHeaderField("Location");
                        connection.disconnect();
                        url = new URL(redirectUrl);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestProperty("User-Agent", userAgent);
                        connection.setRequestProperty("Referer", "https://vidzy.live/");
                        if (isResume) {
                            connection.setRequestProperty("Range", "bytes=" + existingLength + "-");
                        }
                        connection.setConnectTimeout(connectionTimeout);
                        connection.setReadTimeout(readTimeout);
                        responseCode = connection.getResponseCode();
                    }

                    if (responseCode != 200 && responseCode != 206) {
                        throw new IOException("HTTP " + responseCode + " - " + connection.getResponseMessage());
                    }

                    // Si le serveur refuse la reprise et renvoie 200, on recommence à 0
                    if (responseCode == 200) {
                        existingLength = 0;
                        isResume = false;
                    }

                    if (totalBytes <= 0) {
                        totalBytes = connection.getContentLengthLong();
                        if (isResume) totalBytes += existingLength;
                        task.setTotalBytes(totalBytes);
                    }
                    broadcastUpdate(task);

                    // Étape 3: Télécharger avec suivi de progression et limitation de débit
                    try (InputStream in = new BufferedInputStream(connection.getInputStream());
                         FileOutputStream fos = new FileOutputStream(outputFile, isResume)) {

                        byte[] buffer = new byte[8192];
                        long downloadedBytes = existingLength;
                        int bytesRead;
                        long lastUpdateTime = System.currentTimeMillis();
                        long lastDownloaded = downloadedBytes;

                        long bytesSinceLastThrottling = 0;
                        long throttlingStartTime = System.currentTimeMillis();

                        while ((bytesRead = in.read(buffer)) != -1) {
                            if (task.isCancelled()) {
                                log.info("Téléchargement annulé: {}", task.getFileName());
                                fos.close();
                                outputFile.delete();
                                task.setStatus(DownloadStatus.CANCELLED);
                                broadcastUpdate(task);
                                return;
                            }
                            if (task.isPaused()) {
                                log.info("Téléchargement mis en pause: {}", task.getFileName());
                                fos.flush();
                                fos.close();
                                task.setStatus(DownloadStatus.PAUSED);
                                task.setSpeed(0);
                                broadcastUpdate(task);
                                return;
                            }

                            fos.write(buffer, 0, bytesRead);
                            downloadedBytes += bytesRead;
                            task.setDownloadedBytes(downloadedBytes);

                            if (totalBytes > 0) {
                                task.setProgress((double) downloadedBytes / totalBytes * 100);
                            }

                            // Régulation intelligente du débit
                            long speedLimit = task.getMaxSpeedLimit() > 0 ? task.getMaxSpeedLimit() : globalSpeedLimit;
                            if (speedLimit > 0) {
                                bytesSinceLastThrottling += bytesRead;
                                if (bytesSinceLastThrottling >= Math.max(8192, speedLimit / 10)) {
                                    long elapsed = System.currentTimeMillis() - throttlingStartTime;
                                    long expectedTime = (bytesSinceLastThrottling * 1000) / speedLimit;
                                    if (expectedTime > elapsed) {
                                        Thread.sleep(expectedTime - elapsed);
                                    }
                                    bytesSinceLastThrottling = 0;
                                    throttlingStartTime = System.currentTimeMillis();
                                }
                            }

                            long now = System.currentTimeMillis();
                            long elapsed = now - lastUpdateTime;
                            if (elapsed >= 500) {
                                long bytesSinceLast = downloadedBytes - lastDownloaded;
                                task.setSpeed((long) (bytesSinceLast / (elapsed / 1000.0)));
                                lastUpdateTime = now;
                                lastDownloaded = downloadedBytes;
                                broadcastUpdate(task);
                            }
                        }

                        fos.flush();
                        if (totalBytes > 0 && downloadedBytes < totalBytes) {
                            throw new IOException("Téléchargement incomplet : seulement " + downloadedBytes + " octets téléchargés sur " + totalBytes);
                        }
                        if (downloadedBytes <= 0) {
                            throw new IOException("Aucune donnée reçue (0 octet téléchargé).");
                        }
                    }

                    // Étape 4: Télécharger les sous-titres si demandés et disponibles
                    if (task.isDownloadSubtitles() && subtitleUrl != null && !subtitleUrl.isBlank()) {
                        downloadSubtitle(task, subtitleUrl);
                    }

                    // Téléchargement terminé
                    task.setProgress(100.0);
                    task.setStatus(DownloadStatus.COMPLETED);
                    task.setCompletedAt(LocalDateTime.now());
                    task.setSpeed(0);
                    broadcastUpdate(task);

                    log.info("Téléchargement terminé: {} ({})", task.getFileName(), task.getFormattedTotalSize());

                    // Muxing FFmpeg automatique
                    if (task.getSavePathSubtitle() != null) {
                        muxSubtitlesWithFFmpeg(task, task.getSavePathSubtitle());
                    }

                    break; // Réussite! Sortie de la boucle infinie de retry.

                } catch (Exception e) {
                    if (task.isCancelled()) {
                        task.setStatus(DownloadStatus.CANCELLED);
                        broadcastUpdate(task);
                        return;
                    }
                    if (task.isPaused()) {
                        task.setStatus(DownloadStatus.PAUSED);
                        broadcastUpdate(task);
                        return;
                    }

                    int currentRetry = task.getRetryCount();
                    if (currentRetry < maxRetries) {
                        task.setRetryCount(currentRetry + 1);
                        task.setStatus(DownloadStatus.RETRYING);
                        task.setError("Erreur: " + e.getMessage() + ". Retentative " + (currentRetry + 1) + "/" + maxRetries + "...");
                        broadcastUpdate(task);
                        long backoffMs = (long) Math.pow(2, currentRetry) * 2000;
                        log.warn("Téléchargement échoué pour {}, retentative dans {}ms. Erreur: {}", task.getFileName(), backoffMs, e.getMessage());
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            task.setStatus(DownloadStatus.FAILED);
                            task.setError(e.getMessage());
                            broadcastUpdate(task);
                            return;
                        }
                    } else {
                        log.error("Erreur téléchargement {} (essais épuisés): {}", task.getFileName(), e.getMessage());
                        task.setStatus(DownloadStatus.FAILED);
                        task.setError(e.getMessage());
                        broadcastUpdate(task);
                        break;
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        } finally {
            processQueue();
        }
    }

    /**
     * Télécharge le fichier de sous-titres.
     */
    private void downloadSubtitle(DownloadTask task, String subtitleUrl) {
        HttpURLConnection conn = null;
        try {
            log.info("Téléchargement sous-titres: {} pour {}", subtitleUrl, task.getFileName());

            String ext = ".vtt";
            if (subtitleUrl.contains(".srt")) ext = ".srt";
            else if (subtitleUrl.contains(".ass")) ext = ".ass";

            String subFileName = task.getFileName().replaceAll("\\.(mp4|mkv|avi)$", "") + ext;
            String subSavePath = new File(task.getSavePath()).getParent() + File.separator + subFileName;

            URL url = new URL(subtitleUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Referer", "https://vidzy.live/");
            conn.setConnectTimeout(connectionTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                File subFile = new File(subSavePath);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(subFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                    fos.flush();
                }
                task.setSavePathSubtitle(subSavePath); // Enregistrer
                log.info("Sous-titres téléchargés: {}", subFileName);
            } else {
                log.warn("Impossible de télécharger les sous-titres (HTTP {}): {}", responseCode, subtitleUrl);
            }

        } catch (Exception e) {
            log.warn("Erreur téléchargement sous-titres: {}", e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Vérifie la disponibilité de FFmpeg.
     */
    private boolean isFFmpegAvailable() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String command = os.contains("win") ? "where ffmpeg" : "which ffmpeg";
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Assemble la vidéo et les sous-titres dans un MKV à l'aide de FFmpeg.
     */
    private void muxSubtitlesWithFFmpeg(DownloadTask task, String subPath) {
        if (!isFFmpegAvailable()) {
            log.warn("FFmpeg non trouvé dans l'environnement. Muxing ignoré, les fichiers resteront séparés.");
            return;
        }

        File videoFile = new File(task.getSavePath());
        File subFile = new File(subPath);
        if (!videoFile.exists() || !subFile.exists()) return;

        log.info("Lancement du Muxing FFmpeg pour: {}", task.getFileName());
        String baseName = videoFile.getAbsolutePath().replaceAll("\\.(mp4|mkv|avi)$", "");
        String outputFilePath = baseName + ".mkv";
        File outputFile = new File(outputFilePath);

        try {
            // Commande de muxing standard ultra-rapide (sans encodage)
            String[] command = {
                "ffmpeg", "-y",
                "-i", videoFile.getAbsolutePath(),
                "-i", subFile.getAbsolutePath(),
                "-c", "copy",
                "-c:s", "srt",
                outputFilePath
            };

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Consommer les flux de sortie du processus pour éviter tout blocage
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Lecture de la sortie de FFmpeg
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                log.info("Muxing FFmpeg réussi avec succès ! Fichier généré : {}", outputFile.getName());
                
                // Mettre à jour les informations de la tâche
                task.setFileName(outputFile.getName());
                task.setSavePath(outputFile.getAbsolutePath());
                task.setSavePathSubtitle(null); // Plus besoin de sous-titres séparés !

                // Supprimer les résidus vidéo et sous-titres séparés
                videoFile.delete();
                subFile.delete();
            } else {
                log.error("FFmpeg a échoué avec le code de sortie {}. Les fichiers restent séparés.", exitCode);
                if (outputFile.exists()) outputFile.delete();
            }

        } catch (Exception e) {
            log.error("Erreur lors de l'exécution de FFmpeg: {}", e.getMessage());
        }
    }

    /**
     * Envoie les mises à jour aux clients connectés.
     */
    private void broadcastUpdate(DownloadTask task) {
        Map<String, Object> data = taskToMap(task);
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : sseEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("download-update")
                        .data(data));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        sseEmitters.removeAll(deadEmitters);
    }

    public synchronized void processQueue() {
        long activeDownloads = tasks.values().stream()
                .filter(t -> t.getStatus() == DownloadStatus.DOWNLOADING || t.getStatus() == DownloadStatus.RETRYING)
                .count();

        log.info("Traitement de la file d'attente. Actifs: {} / Concurrence Max: {}", activeDownloads, maxConcurrentDownloads);

        if (activeDownloads < maxConcurrentDownloads) {
            List<DownloadTask> pendingTasks = new ArrayList<>();
            for (String id : taskOrder) {
                DownloadTask t = tasks.get(id);
                if (t != null && t.getStatus() == DownloadStatus.PENDING) {
                    pendingTasks.add(t);
                }
            }

            // Trier par date de création pour respecter l'ordre exact d'ajout
            pendingTasks.sort(Comparator.comparing(DownloadTask::getCreatedAt));

            int slotsAvailable = maxConcurrentDownloads - (int) activeDownloads;
            for (int i = 0; i < Math.min(slotsAvailable, pendingTasks.size()); i++) {
                DownloadTask taskToStart = pendingTasks.get(i);
                log.info("Lancement de la tâche en file d'attente: {}", taskToStart.getFileName());
                
                // Marquer comme DOWNLOADING
                taskToStart.setStatus(DownloadStatus.DOWNLOADING);
                broadcastUpdate(taskToStart);
                
                downloadExecutor.execute(() -> executeDownload(taskToStart, taskToStart.getSubtitleUrl()));
            }
        }
    }

    /**
     * Suspend instantanément tous les téléchargements actifs ou en file d'attente.
     */
    public void pauseAllDownloads() {
        log.info("Mise en pause globale de toutes les tâches actives ou en attente...");
        for (DownloadTask task : tasks.values()) {
            if (task.getStatus() == DownloadStatus.DOWNLOADING ||
                task.getStatus() == DownloadStatus.PENDING ||
                task.getStatus() == DownloadStatus.RETRYING ||
                task.getStatus() == DownloadStatus.SCHEDULED) {
                
                task.setPaused(true);
                task.setStatus(DownloadStatus.PAUSED);
                task.setSpeed(0);
                broadcastUpdate(task);
                log.info("Tâche mise en pause: {}", task.getFileName());
            }
        }
        processQueue();
    }

    /**
     * Reprend tous les téléchargements en pause.
     */
    public void resumeAllDownloads() {
        log.info("Reprise globale de tous les téléchargements en pause...");
        for (DownloadTask task : tasks.values()) {
            if (task.getStatus() == DownloadStatus.PAUSED) {
                task.setPaused(false);
                task.setCancelled(false);
                task.setStatus(DownloadStatus.PENDING);
                task.setError(null);
                broadcastUpdate(task);
                log.info("Tâche reprise : {}", task.getFileName());
            }
        }
        processQueue();
    }

    /**
     * Supprime toutes les tâches inactives de l'interface de téléchargement.
     */
    public void clearInactiveTasks() {
        log.info("Nettoyage des tâches inactives (COMPLETED, FAILED, CANCELLED)...");
        List<String> toRemove = new ArrayList<>();
        for (DownloadTask task : tasks.values()) {
            if (task.getStatus() == DownloadStatus.COMPLETED ||
                task.getStatus() == DownloadStatus.FAILED ||
                task.getStatus() == DownloadStatus.CANCELLED) {
                toRemove.add(task.getId());
            }
        }
        for (String id : toRemove) {
            removeTask(id);
        }
    }

    private Map<String, Object> taskToMap(DownloadTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("animeName", task.getAnimeName());
        map.put("episodeNumber", task.getEpisodeNumber());
        map.put("fileName", task.getFileName());
        map.put("status", task.getStatus().name());
        map.put("progress", Math.round(task.getProgress() * 10.0) / 10.0);
        map.put("downloadedSize", task.getFormattedDownloadedSize());
        map.put("totalSize", task.getFormattedTotalSize());
        map.put("speed", task.getFormattedSpeed());
        map.put("error", task.getError());
        map.put("savePath", task.getSavePath());
        map.put("scheduledStartTime", task.getScheduledStartTime() != null ? task.getScheduledStartTime().toString() : null);
        map.put("downloadPageUrl", task.getDownloadPageUrl());
        map.put("directDownloadUrl", task.getDirectDownloadUrl());
        map.put("downloadSubtitles", task.isDownloadSubtitles());
        map.put("maxSpeedLimit", task.getMaxSpeedLimit());
        return map;
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "download";
        return name.replaceAll("[<>:\"/\\\\|?*]", "_")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
}
