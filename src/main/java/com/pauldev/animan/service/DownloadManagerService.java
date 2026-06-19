package com.pauldev.animan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.*;

/**
 * Gestionnaire de téléchargements avec:
 * - Persistance H2 via LibraryService
 * - Téléchargement multi-morceaux myTV (voir-anime.to)
 * - Gestion complète SSE / pause / reprise / retry
 */
@Slf4j
@Service
public class DownloadManagerService {

    private final Executor downloadExecutor;
    private final ScrapingService scrapingService;
    private final VoirAnimeScrapingService voirAnimeService;
    private final LibraryService libraryService;

    @Value("${animan.download-dir}")
    private String downloadDir;
    @Value("${animan.user-agent}")
    private String userAgent;

    @org.springframework.beans.factory.annotation.Autowired
    private ProxyRotatorService proxyRotator;
    @Value("${animan.connection-timeout}")
    private int connectionTimeout;
    @Value("${animan.read-timeout}")
    private int readTimeout;
    @Value("${animan.max-concurrent-downloads}")
    private int maxConcurrentDownloads;

    private boolean plexOrganization = false;

    public boolean isPlexOrganization() {
        return plexOrganization;
    }

    public void setPlexOrganization(boolean plexOrganization) {
        this.plexOrganization = plexOrganization;
    }

    public String getSelectedUserAgent() {
        return proxyRotator != null ? proxyRotator.getSelectedUserAgent() : "Random (Rotator)";
    }

    public void setSelectedUserAgent(String ua) {
        if (proxyRotator != null) {
            proxyRotator.setSelectedUserAgent(ua);
        }
    }

    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final List<String> taskOrder = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();
    private volatile long globalSpeedLimit = 0;

    public static class ResolvedVideoInfo {
        public String directUrl;
        public String subtitleUrl;
        public String subtitlesJson; // [{url,label,lang},...]
        public long size;
        public String formattedSize;
        public String serverType;           // "myTV", "stape", "vidzy"
        public String videoChunksJson;      // pour myTV
        public String videoQualitiesJson;   // pour myTV
    }

    private final Map<String, ResolvedVideoInfo> resolvedInfoCache = new ConcurrentHashMap<>();

    public DownloadManagerService(@Qualifier("downloadExecutor") Executor downloadExecutor,
                                   ScrapingService scrapingService,
                                   VoirAnimeScrapingService voirAnimeService,
                                   LibraryService libraryService) {
        this.downloadExecutor = downloadExecutor;
        this.scrapingService = scrapingService;
        this.voirAnimeService = voirAnimeService;
        this.libraryService = libraryService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            List<com.pauldev.animan.model.DownloadTaskEntity> unfinished = libraryService.getUnfinishedTasks();
            for (com.pauldev.animan.model.DownloadTaskEntity entity : unfinished) {
                DownloadTask task = entity.toTask();
                if (task.getStatus() == DownloadStatus.DOWNLOADING || task.getStatus() == DownloadStatus.RETRYING) {
                    task.setStatus(DownloadStatus.PAUSED);
                    task.setSpeed(0);
                    libraryService.saveTask(task);
                }
                tasks.put(task.getId(), task);
                taskOrder.add(task.getId());
            }
            log.info("Chargé {} tâche(s) non terminée(s) depuis H2 au démarrage", tasks.size());
            processQueue();
        } catch (Exception e) {
            log.error("Erreur lors de l'initialisation des tâches depuis H2: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Gestion des limites
    // =========================================================================

    public void setGlobalSpeedLimit(long limitBytesPerSec) {
        this.globalSpeedLimit = limitBytesPerSec;
        tasks.values().forEach(t -> {
            if (t.getStatus() == DownloadStatus.DOWNLOADING || t.getStatus() == DownloadStatus.PENDING)
                t.setMaxSpeedLimit(limitBytesPerSec);
        });
    }

    public long getGlobalSpeedLimit() { return globalSpeedLimit; }
    public int getMaxConcurrentDownloads() { return maxConcurrentDownloads; }
    public void setMaxConcurrentDownloads(int limit) { this.maxConcurrentDownloads = limit; processQueue(); }
    public boolean setTaskSpeedLimit(String taskId, long limit) {
        DownloadTask t = tasks.get(taskId);
        if (t != null) { t.setMaxSpeedLimit(limit); broadcastUpdate(t); return true; }
        return false;
    }

    // =========================================================================
    // Détection de doublons et taille
    // =========================================================================

    public File checkExistingFile(String animeName, String fileName, int episodeNumber) {
        if (animeName == null || fileName == null) return null;
        String animeDirPath;
        String finalFileName;

        if (plexOrganization) {
            animeDirPath = downloadDir + File.separator + sanitizeFileName(animeName) + File.separator + "Season 01";
            String ext = ".mp4";
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx != -1) {
                ext = fileName.substring(dotIdx);
            }
            String epNumStr = String.format("%02d", episodeNumber);
            finalFileName = sanitizeFileName(animeName) + " - S01E" + epNumStr + ext;
        } else {
            animeDirPath = downloadDir + File.separator + sanitizeFileName(animeName);
            finalFileName = sanitizeFileName(fileName);
        }

        // 1. Vérifier le fichier exact
        File file = new File(animeDirPath, finalFileName);
        if (file.exists()) return file;

        // 2. Vérifier les extensions alternatives (.mp4, .mkv, .avi)
        String baseName = finalFileName.replaceAll("\\.(mp4|mkv|avi)$", "");
        for (String ext : List.of(".mp4", ".mkv", ".avi")) {
            File altFile = new File(animeDirPath, baseName + ext);
            if (altFile.exists()) return altFile;
        }

        return null;
    }

    public long getRemoteFileSize(String directUrl) {
        if (directUrl == null || directUrl.isBlank()) return -1;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(directUrl);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            long size = conn.getContentLengthLong();
            conn.disconnect();
            if (size > 0) return size;

            // Essai avec Range: bytes=0-0
            conn = openConnection(directUrl);
            conn.setRequestProperty("Range", "bytes=0-0");
            code = conn.getResponseCode();
            if (code == 200 || code == 206) {
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1));
                } else {
                    size = conn.getContentLengthLong();
                }
            }
            return size;
        } catch (Exception e) {
            log.debug("Impossible d'obtenir la taille distante de {}: {}", directUrl, e.getMessage());
            return -1;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    // =========================================================================
    // Résolution épisode (cache)
    // =========================================================================

    public ResolvedVideoInfo getOrResolveEpisodeInfo(String downloadPageUrl) {
        if (downloadPageUrl == null || downloadPageUrl.isBlank()) return null;
        if (resolvedInfoCache.containsKey(downloadPageUrl)) return resolvedInfoCache.get(downloadPageUrl);

        try {
            ResolvedVideoInfo info = new ResolvedVideoInfo();
            Map<String, String> downloadInfo;

            boolean isVoirAnime = downloadPageUrl.contains("voir-anime");
            if (isVoirAnime) {
                downloadInfo = voirAnimeService.extractDownloadInfo(downloadPageUrl);
                info.serverType = downloadInfo.getOrDefault("serverType", "unknown");
                info.videoChunksJson = downloadInfo.getOrDefault("videoChunks", "[]");
                info.videoQualitiesJson = downloadInfo.getOrDefault("videoQualities", "{}");
            } else {
                downloadInfo = scrapingService.extractDownloadInfo(downloadPageUrl);
                info.serverType = "vidzy";
                info.videoChunksJson = "[]";
                info.videoQualitiesJson = "{}";
            }

            info.directUrl = downloadInfo.get("videoUrl");
            info.subtitleUrl = downloadInfo.getOrDefault("subtitleUrl", "");
            info.subtitlesJson = downloadInfo.getOrDefault("subtitles", "[]");

            if (info.directUrl != null && !info.directUrl.isEmpty()) {
                info.size = getRemoteFileSize(info.directUrl);
            }

            if ("myTV".equals(info.serverType) && info.videoChunksJson != null) {
                List<String> chunks = parseJsonArray(info.videoChunksJson);
                long totalSize = 0;
                for (String chunkUrl : chunks) {
                    long sz = getRemoteFileSize(chunkUrl);
                    if (sz > 0) totalSize += sz;
                }
                if (totalSize > 0) {
                    info.size = totalSize;
                }
            }

            info.formattedSize = info.size > 0 ? formatBytes(info.size) : "Taille inconnue";

            resolvedInfoCache.put(downloadPageUrl, info);
            return info;
        } catch (Exception e) {
            log.error("Erreur résolution épisode: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Démarrage des téléchargements
    // =========================================================================

    public DownloadTask startDownload(String animeName, int episodeNumber,
                                       String downloadPageUrl, String fileName,
                                       String subtitleUrl, LocalDateTime scheduledStartTime,
                                       long speedLimit, boolean downloadSubtitles) {
        long initialTotalBytes = 0;
        String resolvedSubtitlesJson = "[]";
        ResolvedVideoInfo cached = resolvedInfoCache.get(downloadPageUrl);
        if (cached != null) {
            if (cached.size > 0) initialTotalBytes = cached.size;
            if (cached.subtitlesJson != null) resolvedSubtitlesJson = cached.subtitlesJson;
        }

        DownloadTask task = DownloadTask.builder()
                .id(UUID.randomUUID().toString())
                .animeName(animeName).episodeNumber(episodeNumber)
                .fileName(sanitizeFileName(fileName)).fileUrl(downloadPageUrl)
                .downloadPageUrl(downloadPageUrl).subtitleUrl(subtitleUrl)
                .subtitlesJson(resolvedSubtitlesJson)
                .downloadSubtitles(downloadSubtitles)
                .status(scheduledStartTime != null && scheduledStartTime.isAfter(LocalDateTime.now())
                        ? DownloadStatus.SCHEDULED : DownloadStatus.PENDING)
                .scheduledStartTime(scheduledStartTime)
                .maxSpeedLimit(speedLimit > 0 ? speedLimit : globalSpeedLimit)
                .totalBytes(initialTotalBytes).progress(0)
                .build();

        String animeDirPath;
        String finalFileName;

        if (plexOrganization) {
            animeDirPath = downloadDir + File.separator + sanitizeFileName(animeName) + File.separator + "Season 01";
            
            // Extrait l'extension d'origine, par défaut .mp4
            String ext = ".mp4";
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx != -1) {
                ext = fileName.substring(dotIdx);
            }
            
            String epNumStr = String.format("%02d", episodeNumber);
            finalFileName = sanitizeFileName(animeName) + " - S01E" + epNumStr + ext;
            task.setFileName(finalFileName);
        } else {
            animeDirPath = downloadDir + File.separator + sanitizeFileName(animeName);
            finalFileName = task.getFileName();
        }

        new File(animeDirPath).mkdirs();
        task.setSavePath(animeDirPath + File.separator + finalFileName);

        tasks.put(task.getId(), task);
        taskOrder.add(task.getId());
        libraryService.saveTask(task);

        if (task.getStatus() == DownloadStatus.PENDING) processQueue();
        broadcastUpdate(task);
        return task;
    }

    public DownloadTask startDownload(String animeName, int episodeNumber,
                                       String downloadPageUrl, String fileName,
                                       String subtitleUrl, LocalDateTime scheduledStartTime, long speedLimit) {
        return startDownload(animeName, episodeNumber, downloadPageUrl, fileName, subtitleUrl, scheduledStartTime, speedLimit, false);
    }

    public DownloadTask startDownload(String animeName, int episodeNumber, String downloadPageUrl, String fileName, String subtitleUrl) {
        return startDownload(animeName, episodeNumber, downloadPageUrl, fileName, subtitleUrl, null, 0, false);
    }

    public DownloadTask startDownload(String animeName, int episodeNumber, String downloadPageUrl, String fileName) {
        return startDownload(animeName, episodeNumber, downloadPageUrl, fileName, null, null, 0, false);
    }

    public List<DownloadTask> startBatchDownload(String animeName, List<Map<String, String>> episodes) {
        List<DownloadTask> started = new ArrayList<>();
        for (Map<String, String> ep : episodes) {
            int epNum = Integer.parseInt(ep.getOrDefault("number", "0"));
            String url = ep.getOrDefault("url", "");
            String name = ep.getOrDefault("fileName", animeName + " - Episode " + epNum + ".mp4");
            String subUrl = ep.getOrDefault("subtitleUrl", "");
            boolean dlSubs = Boolean.parseBoolean(ep.getOrDefault("downloadSubtitles", "false"));

            // Vérification anti-doublon pour le lot
            File existing = checkExistingFile(animeName, name, epNum);
            if (existing != null) {
                ResolvedVideoInfo info = getOrResolveEpisodeInfo(url);
                if (info != null && info.size > 0 && existing.length() == info.size) {
                    log.info("Batch: Épisode {} de {} existe déjà avec la même taille ({}), ignoré.", epNum, animeName, info.formattedSize);
                    continue;
                }
            }

            LocalDateTime scheduledTime = null;
            String scheduledTimeStr = ep.get("scheduledTime");
            if (scheduledTimeStr != null && !scheduledTimeStr.isBlank()) {
                try { scheduledTime = LocalDateTime.parse(scheduledTimeStr); } catch (Exception ignored) {}
            }
            long speedLimit = 0;
            try { if (ep.get("speedLimit") != null) speedLimit = Long.parseLong(ep.get("speedLimit")); }
            catch (Exception ignored) {}

            if (!url.isBlank()) started.add(startDownload(animeName, epNum, url, name, subUrl, scheduledTime, speedLimit, dlSubs));
        }
        return started;
    }

    // =========================================================================
    // Contrôle des tâches
    // =========================================================================

    @Scheduled(fixedDelay = 5000)
    public void checkScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        tasks.values().forEach(t -> {
            if (t.getStatus() == DownloadStatus.SCHEDULED && t.getScheduledStartTime() != null
                    && t.getScheduledStartTime().isBefore(now)) {
                t.setStatus(DownloadStatus.PENDING);
                broadcastUpdate(t);
                processQueue();
            }
        });
    }

    public boolean cancelDownload(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t == null) return false;
        EnumSet<DownloadStatus> active = EnumSet.of(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING,
                DownloadStatus.RETRYING, DownloadStatus.SCHEDULED);
        if (active.contains(t.getStatus())) {
            t.setCancelled(true); t.setStatus(DownloadStatus.CANCELLED);
            broadcastUpdate(t); persistTask(t); processQueue(); return true;
        }
        return false;
    }

    public boolean pauseDownload(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t == null) return false;
        EnumSet<DownloadStatus> active = EnumSet.of(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING,
                DownloadStatus.RETRYING, DownloadStatus.SCHEDULED);
        if (active.contains(t.getStatus())) {
            t.setPaused(true); t.setStatus(DownloadStatus.PAUSED); t.setSpeed(0);
            broadcastUpdate(t); processQueue(); return true;
        }
        return false;
    }

    public boolean resumeDownload(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t == null) return false;
        EnumSet<DownloadStatus> resumable = EnumSet.of(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED);
        if (resumable.contains(t.getStatus())) {
            t.setPaused(false); t.setCancelled(false); t.setStatus(DownloadStatus.PENDING); t.setError(null);
            broadcastUpdate(t); processQueue(); return true;
        }
        return false;
    }

    public boolean removeTask(String taskId) {
        DownloadTask t = tasks.remove(taskId);
        if (t != null) {
            taskOrder.remove(taskId);
            if (t.getStatus() == DownloadStatus.DOWNLOADING || t.getStatus() == DownloadStatus.RETRYING)
                t.setCancelled(true);
            processQueue(); return true;
        }
        return false;
    }

    public List<DownloadTask> getAllTasks() {
        List<DownloadTask> ordered = new ArrayList<>();
        for (String id : taskOrder) { DownloadTask t = tasks.get(id); if (t != null) ordered.add(t); }
        return ordered;
    }

    public DownloadTask getTask(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t != null) return t;
        return libraryService.getTaskEntity(taskId)
                .map(com.pauldev.animan.model.DownloadTaskEntity::toTask)
                .orElse(null);
    }

    public void pauseAllDownloads() {
        tasks.values().forEach(t -> {
            if (EnumSet.of(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING,
                    DownloadStatus.RETRYING, DownloadStatus.SCHEDULED).contains(t.getStatus())) {
                t.setPaused(true); t.setStatus(DownloadStatus.PAUSED); t.setSpeed(0); broadcastUpdate(t);
            }
        });
        processQueue();
    }

    public void resumeAllDownloads() {
        tasks.values().forEach(t -> {
            if (t.getStatus() == DownloadStatus.PAUSED) {
                t.setPaused(false); t.setCancelled(false); t.setStatus(DownloadStatus.PENDING); t.setError(null); broadcastUpdate(t);
            }
        });
        processQueue();
    }

    public void clearInactiveTasks() {
        EnumSet<DownloadStatus> inactive = EnumSet.of(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED);
        new ArrayList<>(tasks.keySet()).forEach(id -> { if (inactive.contains(tasks.get(id).getStatus())) removeTask(id); });
    }

    // =========================================================================
    // SSE
    // =========================================================================

    public SseEmitter createSseEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        sseEmitters.add(emitter);
        emitter.onCompletion(() -> sseEmitters.remove(emitter));
        emitter.onTimeout(() -> sseEmitters.remove(emitter));
        emitter.onError(e -> sseEmitters.remove(emitter));
        getAllTasks().forEach(t -> {
            try { emitter.send(SseEmitter.event().name("download-update").data(taskToMap(t))); }
            catch (IOException e) { sseEmitters.remove(emitter); }
        });
        return emitter;
    }

    // =========================================================================
    // Exécution des téléchargements
    // =========================================================================

    private void executeDownload(DownloadTask task, String subtitleUrl) {
        try {
            int maxRetries = 3;
            task.setRetryCount(0);
            task.setSubtitleUrl(subtitleUrl);

            while (true) {
                HttpURLConnection connection = null;
                try {
                    if (task.isCancelled()) { task.setStatus(DownloadStatus.CANCELLED); broadcastUpdate(task); return; }
                    if (task.isPaused()) { task.setStatus(DownloadStatus.PAUSED); broadcastUpdate(task); return; }

                    task.setStatus(DownloadStatus.DOWNLOADING);
                    broadcastUpdate(task);

                    // Résoudre l'URL directe
                    String directUrl = task.getFileUrl();
                    boolean isVoirAnime = directUrl != null && directUrl.contains("voir-anime");
                    String videoChunksJson = "[]";
                    String serverType = "vidzy";

                    if (isVoirAnime) {
                        Map<String, String> info = voirAnimeService.extractDownloadInfo(directUrl);
                        directUrl = info.get("videoUrl");
                        serverType = info.getOrDefault("serverType", "unknown");
                        videoChunksJson = info.getOrDefault("videoChunks", "[]");
                        task.setFileUrl(directUrl);
                        task.setDirectDownloadUrl(directUrl);
                        if ((subtitleUrl == null || subtitleUrl.isBlank()) && !info.getOrDefault("subtitleUrl", "").isBlank()) {
                            subtitleUrl = info.get("subtitleUrl");
                            task.setSubtitleUrl(subtitleUrl);
                        }
                        if (task.getSubtitlesJson() == null || task.getSubtitlesJson().isEmpty() || task.getSubtitlesJson().equals("[]")) {
                            task.setSubtitlesJson(info.getOrDefault("subtitles", "[]"));
                        }
                    } else if (directUrl != null && needsScrapingResolution(directUrl)) {
                        Map<String, String> info = scrapingService.extractDownloadInfo(directUrl);
                        directUrl = info.get("videoUrl");
                        task.setFileUrl(directUrl);
                        task.setDirectDownloadUrl(directUrl);
                        if ((subtitleUrl == null || subtitleUrl.isBlank()) && !info.getOrDefault("subtitleUrl", "").isBlank()) {
                            subtitleUrl = info.get("subtitleUrl");
                            task.setSubtitleUrl(subtitleUrl);
                        }
                        if (task.getSubtitlesJson() == null || task.getSubtitlesJson().isEmpty() || task.getSubtitlesJson().equals("[]")) {
                            task.setSubtitlesJson(info.getOrDefault("subtitles", "[]"));
                        }
                    } else {
                        task.setDirectDownloadUrl(directUrl);
                    }

                    // myTV multi-morceaux
                    if ("myTV".equals(serverType)) {
                        List<String> chunks = parseJsonArray(videoChunksJson);
                        if (!chunks.isEmpty()) {
                            downloadMyTvChunks(task, chunks, subtitleUrl);
                            return;
                        }
                    }

                    // Téléchargement standard (Vidzy / Stape / direct)
                    downloadSingleFile(task, directUrl, subtitleUrl);
                    break;

                } catch (Exception e) {
                    if (task.isCancelled()) { task.setStatus(DownloadStatus.CANCELLED); broadcastUpdate(task); return; }
                    if (task.isPaused()) { task.setStatus(DownloadStatus.PAUSED); broadcastUpdate(task); return; }

                    int retry = task.getRetryCount();
                    if (retry < maxRetries) {
                        task.setRetryCount(retry + 1);
                        task.setStatus(DownloadStatus.RETRYING);
                        task.setError("Erreur: " + e.getMessage() + ". Tentative " + (retry + 1) + "/" + maxRetries);
                        broadcastUpdate(task);
                        try { Thread.sleep((long) Math.pow(2, retry) * 2000); } catch (InterruptedException ie) {
                            task.setStatus(DownloadStatus.FAILED); task.setError(e.getMessage()); broadcastUpdate(task); return;
                        }
                    } else {
                        task.setStatus(DownloadStatus.FAILED); task.setError(e.getMessage());
                        broadcastUpdate(task); persistTask(task); break;
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        } finally {
            processQueue();
        }
    }

    /**
     * Télécharge un fichier unique avec support Range (reprise).
     */
    private void downloadSingleFile(DownloadTask task, String directUrl, String subtitleUrl) throws Exception {
        boolean acceptRanges = false;
        long totalBytes = -1;

        try {
            HttpURLConnection headConn = openConnection(directUrl);
            headConn.setRequestMethod("HEAD");
            int code = headConn.getResponseCode();
            acceptRanges = "bytes".equalsIgnoreCase(headConn.getHeaderField("Accept-Ranges"));
            totalBytes = headConn.getContentLengthLong();
            headConn.disconnect();
        } catch (Exception e) { log.debug("HEAD failed: {}", e.getMessage()); }

        if (!acceptRanges) {
            try {
                HttpURLConnection testConn = openConnection(directUrl);
                testConn.setRequestProperty("Range", "bytes=0-0");
                int testCode = testConn.getResponseCode();
                acceptRanges = (testCode == 206);
                if (totalBytes <= 0) {
                    String cr = testConn.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/"))
                        totalBytes = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1));
                }
                testConn.disconnect();
            } catch (Exception ignored) {}
        }

        task.setSupportsResume(acceptRanges);
        if (totalBytes > 0) { task.setTotalBytes(totalBytes); broadcastUpdate(task); }

        File outputFile = new File(task.getSavePath());
        long existingLength = 0;
        boolean isResume = false;

        // Si le fichier local existe déjà et est complet
        if (totalBytes > 0 && outputFile.exists() && outputFile.length() >= totalBytes) {
            finalizeTask(task);
            return;
        }

        if (acceptRanges && outputFile.exists() && outputFile.length() > 0 && outputFile.length() < totalBytes) {
            existingLength = outputFile.length();
            isResume = true;
        }

        if (totalBytes > 0) {
            long requiredSpace = totalBytes - existingLength;
            File disk = new File(downloadDir);
            long freeSpace = disk.getFreeSpace();
            if (requiredSpace > 0 && freeSpace < requiredSpace) {
                throw new IOException("Espace disque insuffisant (Requis: " + formatBytes(requiredSpace) + ", Disponible: " + formatBytes(freeSpace) + ")");
            }
        }

        HttpURLConnection connection = openConnection(directUrl);
        if (isResume) connection.setRequestProperty("Range", "bytes=" + existingLength + "-");
        int responseCode = connection.getResponseCode();

        // Re-résoudre si lien expiré
        if ((responseCode == 403 || responseCode == 404 || responseCode == 410 || responseCode == 401)
                && task.getDownloadPageUrl() != null) {
            connection.disconnect();
            boolean isVoirAnime = task.getDownloadPageUrl().contains("voir-anime");
            Map<String, String> info = isVoirAnime
                    ? voirAnimeService.extractDownloadInfo(task.getDownloadPageUrl())
                    : scrapingService.extractDownloadInfo(task.getDownloadPageUrl());
            directUrl = info.get("videoUrl");
            task.setFileUrl(directUrl); task.setDirectDownloadUrl(directUrl);
            connection = openConnection(directUrl);
            if (isResume) connection.setRequestProperty("Range", "bytes=" + existingLength + "-");
            responseCode = connection.getResponseCode();
        }

        if (responseCode == 301 || responseCode == 302) {
            String loc = connection.getHeaderField("Location");
            connection.disconnect();
            connection = openConnection(loc);
            if (isResume) connection.setRequestProperty("Range", "bytes=" + existingLength + "-");
            responseCode = connection.getResponseCode();
        }

        if (responseCode != 200 && responseCode != 206) throw new IOException("HTTP " + responseCode);
        if (responseCode == 200) { existingLength = 0; isResume = false; }
        if (totalBytes <= 0) {
            totalBytes = connection.getContentLengthLong();
            if (isResume) totalBytes += existingLength;
            task.setTotalBytes(totalBytes);
        }
        broadcastUpdate(task);

        streamToFile(task, connection, outputFile, existingLength, totalBytes, isResume);

        if (task.isCancelled() || task.isPaused()) {
            connection.disconnect();
            return;
        }

        // Sous-titres
        if (task.isDownloadSubtitles())
            downloadSubtitles(task, task.getSubtitlesJson());

        finalizeTask(task);
        connection.disconnect();
    }

    /**
     * Télécharge plusieurs morceaux myTV et les concatène.
     */
    private void downloadMyTvChunks(DownloadTask task, List<String> chunks, String subtitleUrl) throws Exception {
        log.info("myTV: {} morceau(x) à télécharger pour {}", chunks.size(), task.getFileName());
        String basePath = task.getSavePath().replaceAll("\\.(mp4|mkv)$", "");
        List<File> chunkFiles = new ArrayList<>();
        long totalAllChunks = 0;

        // Estimer la taille totale
        for (String chunkUrl : chunks) {
            try {
                HttpURLConnection hc = openConnection(chunkUrl);
                hc.setRequestMethod("HEAD");
                long sz = hc.getContentLengthLong();
                if (sz > 0) totalAllChunks += sz;
                hc.disconnect();
            } catch (Exception ignored) {}
        }
        if (totalAllChunks > 0) {
            task.setTotalBytes(totalAllChunks);
            broadcastUpdate(task);
            File disk = new File(downloadDir);
            long freeSpace = disk.getFreeSpace();
            if (freeSpace < totalAllChunks) {
                throw new IOException("Espace disque insuffisant (Requis: " + formatBytes(totalAllChunks) + ", Disponible: " + formatBytes(freeSpace) + ")");
            }
        }

        long downloadedTotal = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (task.isCancelled()) { task.setStatus(DownloadStatus.CANCELLED); broadcastUpdate(task); return; }
            if (task.isPaused()) { task.setStatus(DownloadStatus.PAUSED); broadcastUpdate(task); return; }

            String chunkUrl = chunks.get(i);
            File chunkFile = new File(basePath + "_part" + (i + 1) + ".tmp");
            chunkFiles.add(chunkFile);

            log.info("Téléchargement morceau {}/{}: {}", i + 1, chunks.size(), chunkUrl);
            HttpURLConnection conn = openConnection(chunkUrl);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code + " pour morceau " + (i + 1));

            long chunkSize = conn.getContentLengthLong();
            final long fDownloadedTotal = downloadedTotal;
            final long fTotalAllChunks = totalAllChunks;

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(chunkFile)) {
                byte[] buf = new byte[8192];
                int read;
                long chunkDownloaded = 0;
                long lastUpdate = System.currentTimeMillis();
                long lastBytes = fDownloadedTotal;

                while ((read = in.read(buf)) != -1) {
                    if (task.isCancelled()) { fos.close(); conn.disconnect(); task.setStatus(DownloadStatus.CANCELLED); broadcastUpdate(task); return; }
                    if (task.isPaused()) { fos.close(); conn.disconnect(); task.setStatus(DownloadStatus.PAUSED); task.setSpeed(0); broadcastUpdate(task); return; }
                    fos.write(buf, 0, read);
                    chunkDownloaded += read;
                    long globalDownloaded = fDownloadedTotal + chunkDownloaded;
                    task.setDownloadedBytes(globalDownloaded);
                    if (fTotalAllChunks > 0) task.setProgress((double) globalDownloaded / fTotalAllChunks * 100);

                    applyThrottle(task, read);

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate >= 500) {
                        task.setSpeed((long) ((globalDownloaded - lastBytes) / ((now - lastUpdate) / 1000.0)));
                        lastUpdate = now; lastBytes = globalDownloaded; broadcastUpdate(task);
                    }
                }
                fos.flush();
                downloadedTotal += chunkDownloaded;
            }
            conn.disconnect();
        }

        // Concaténer les morceaux
        log.info("Concaténation de {} morceaux en {}", chunkFiles.size(), task.getFileName());
        File outputFile = new File(task.getSavePath());
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (File chunk : chunkFiles) {
                try (FileInputStream fis = new FileInputStream(chunk)) {
                    byte[] buf = new byte[65536];
                    int read;
                    while ((read = fis.read(buf)) != -1) fos.write(buf, 0, read);
                }
                chunk.delete();
            }
            fos.flush();
        }

        if (task.isDownloadSubtitles())
            downloadSubtitles(task, task.getSubtitlesJson());

        finalizeTask(task);
    }

    private void streamToFile(DownloadTask task, HttpURLConnection conn, File outputFile,
                               long existingLength, long totalBytes, boolean isResume) throws Exception {
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(outputFile, isResume)) {

            byte[] buffer = new byte[8192];
            long downloadedBytes = existingLength;
            int bytesRead;
            long lastUpdateTime = System.currentTimeMillis();
            long lastDownloaded = downloadedBytes;

            while ((bytesRead = in.read(buffer)) != -1) {
                if (task.isCancelled()) {
                    fos.close(); outputFile.delete(); task.setStatus(DownloadStatus.CANCELLED); broadcastUpdate(task); return;
                }
                if (task.isPaused()) {
                    fos.flush(); fos.close(); task.setStatus(DownloadStatus.PAUSED); task.setSpeed(0); broadcastUpdate(task); return;
                }
                fos.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;
                task.setDownloadedBytes(downloadedBytes);
                if (totalBytes > 0) task.setProgress((double) downloadedBytes / totalBytes * 100);

                applyThrottle(task, bytesRead);

                long now = System.currentTimeMillis();
                if (now - lastUpdateTime >= 500) {
                    task.setSpeed((long) ((downloadedBytes - lastDownloaded) / ((now - lastUpdateTime) / 1000.0)));
                    lastUpdateTime = now; lastDownloaded = downloadedBytes; broadcastUpdate(task);
                }
            }
            fos.flush();
            if (totalBytes > 0 && downloadedBytes < totalBytes)
                throw new IOException("Téléchargement incomplet: " + downloadedBytes + "/" + totalBytes);
            if (downloadedBytes <= 0) throw new IOException("0 octet reçu");
        }
    }

    private void applyThrottle(DownloadTask task, int bytesRead) throws InterruptedException {
        long speedLimit = task.getMaxSpeedLimit() > 0 ? task.getMaxSpeedLimit() : globalSpeedLimit;
        if (speedLimit <= 0) return;

        if (task.getThrottleStartTimeMs() == 0) {
            task.setThrottleStartTimeMs(System.currentTimeMillis());
            task.setThrottleBytesWritten(0);
        }

        task.setThrottleBytesWritten(task.getThrottleBytesWritten() + bytesRead);
        long elapsedMs = System.currentTimeMillis() - task.getThrottleStartTimeMs();
        if (elapsedMs == 0) elapsedMs = 1;

        long expectedTimeMs = (task.getThrottleBytesWritten() * 1000) / speedLimit;
        long sleepTimeMs = expectedTimeMs - elapsedMs;

        if (sleepTimeMs > 0) {
            Thread.sleep(Math.min(sleepTimeMs, 1000));
        }

        if (elapsedMs > 2000 || task.getThrottleBytesWritten() > 1024 * 1024) {
            task.setThrottleStartTimeMs(System.currentTimeMillis());
            task.setThrottleBytesWritten(0);
        }
    }

    private void finalizeTask(DownloadTask task) {
        task.setProgress(100.0);
        task.setSpeed(0);

        if (task.getSavePathSubtitle() != null && isFFmpegAvailable()) {
            task.setStatus(DownloadStatus.MUXING);
            broadcastUpdate(task);
            persistTask(task);
            muxSubtitlesWithFFmpeg(task, task.getSavePathSubtitle());
        }

        task.setStatus(DownloadStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        broadcastUpdate(task);
        persistTask(task);

        // Marquer comme téléchargé dans la progression
        libraryService.markAsDownloaded(
            task.getDownloadPageUrl() != null ? task.getDownloadPageUrl() : "",
            task.getAnimeName(), task.getEpisodeNumber(), "");
        log.info("Téléchargement terminé: {} ({})", task.getFileName(), task.getFormattedTotalSize());
        
        if (plexOrganization) {
            generateNfoFiles(task);
        }
    }

    private void persistTask(DownloadTask task) {
        try { libraryService.saveTask(task); } catch (Exception e) { log.warn("Persist task failed: {}", e.getMessage()); }
    }

    /**
     * Détermine si une URL doit être résolue via le scraper (ScrapingService)
     * pour obtenir le lien de téléchargement direct.
     *
     * Règles :
     * - Les pages embed vidzy.live (/e/..., /d/..., embed-...) → OUI, besoin de scraping
     * - Les liens CDN vidzy.cc (u14.vidzy.cc/v/...) → NON, déjà un lien direct
     * - Les pages luluvdo.com → OUI, besoin de scraping
     * - Les pages french-manga.net → OUI, besoin de scraping
     * - Toute URL sans extension vidéo reconnue → OUI, probablement une page
     * - URLs se terminant par .mp4, .mkv, .avi (avec ou sans query string) → NON
     */
    private boolean needsScrapingResolution(String url) {
        if (url == null || url.isBlank()) return false;
        // Déjà un lien direct si l'extension est reconnue (CDN vidzy.cc, etc.)
        if (url.matches(".*\\.(mp4|mkv|avi|webm)(\\?.*)?$")) return false;
        // Pages embed / de téléchargement qui nécessitent le scraper
        if (url.contains("vidzy.live")) return true;
        if (url.contains("luluvdo.com")) return true;
        // Pages french-manga.net (source principale)
        if (url.contains("french-manga.net")) return true;
        // Vidzy.cc peut aussi héberger des pages embed (pas seulement le CDN)
        // On distingue : vidzy.cc/v/ = CDN direct ; vidzy.cc/e/ ou /d/ = embed
        if (url.contains("vidzy.cc") && (url.contains("/e/") || url.contains("/d/") || url.contains("embed-"))) return true;
        // Toute autre URL sans extension vidéo → probablement une page à scraper
        return !url.matches(".*\\.(mp4|mkv|avi|webm|ts|m4v)(\\?.*)?$");
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        String ua = proxyRotator != null ? proxyRotator.getUserAgent() : userAgent;
        java.net.Proxy proxy = proxyRotator != null ? proxyRotator.getRandomProxy() : null;
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) (proxy != null ? u.openConnection(proxy) : u.openConnection());
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Referer", "https://vidzy.live/");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setConnectTimeout(connectionTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private void downloadSubtitles(DownloadTask task, String subtitlesJson) {
        if (!task.isDownloadSubtitles()) return;
        List<Map<String, String>> subs = new ArrayList<>();
        
        if (subtitlesJson != null && !subtitlesJson.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                subs = mapper.readValue(subtitlesJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                log.debug("Erreur parsing subtitlesJson: {}", e.getMessage());
            }
        }
        
        if (subs.isEmpty() && task.getSubtitleUrl() != null && !task.getSubtitleUrl().isBlank()) {
            Map<String, String> defaultSub = new HashMap<>();
            defaultSub.put("url", task.getSubtitleUrl());
            defaultSub.put("label", "Sous-titres");
            defaultSub.put("lang", "fr");
            subs.add(defaultSub);
        }
        
        if (subs.isEmpty()) return;
        
        List<String> downloadedPaths = new ArrayList<>();
        for (Map<String, String> sub : subs) {
            String url = sub.get("url");
            String label = sub.getOrDefault("label", "Sous-titres");
            if (url == null || url.isBlank()) continue;
            
            try {
                String ext = url.contains(".srt") ? ".srt" : url.contains(".ass") ? ".ass" : ".vtt";
                String sanitizedLabel = label.replaceAll("[<>:\"/\\\\|?*]", "_");
                String subFileName = task.getFileName().replaceAll("\\.(mp4|mkv|avi)$", "") + "." + sanitizedLabel + ext;
                String subSavePath = new File(task.getSavePath()).getParent() + File.separator + subFileName;

                HttpURLConnection conn = openConnection(url);
                if (conn.getResponseCode() == 200) {
                    try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(subSavePath)) {
                        byte[] buf = new byte[4096]; int read;
                        while ((read = in.read(buf)) != -1) fos.write(buf, 0, read);
                    }
                    downloadedPaths.add(subSavePath);
                    log.info("Sous-titres téléchargés: {}", subFileName);
                }
                conn.disconnect();
            } catch (Exception e) {
                log.warn("Erreur téléchargement sous-titres {}: {}", label, e.getMessage());
            }
        }
        
        if (!downloadedPaths.isEmpty()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                task.setSavePathSubtitle(mapper.writeValueAsString(downloadedPaths));
            } catch (Exception e) {
                task.setSavePathSubtitle(downloadedPaths.get(0));
            }
        }
    }

    private List<String> parseSubtitlePaths(String pathField) {
        if (pathField == null || pathField.isBlank()) return Collections.emptyList();
        if (pathField.startsWith("[") && pathField.endsWith("]")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(pathField, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                // fallback
            }
        }
        return Collections.singletonList(pathField);
    }

    private void muxSubtitlesWithFFmpeg(DownloadTask task, String subPath) {
        if (!isFFmpegAvailable()) { log.warn("FFmpeg non trouvé. Muxing ignoré."); return; }
        File videoFile = new File(task.getSavePath());
        if (!videoFile.exists()) return;

        List<String> subPaths = parseSubtitlePaths(subPath);
        if (subPaths.isEmpty()) return;

        List<File> subFiles = new ArrayList<>();
        for (String sp : subPaths) {
            File sf = new File(sp);
            if (sf.exists()) subFiles.add(sf);
        }
        if (subFiles.isEmpty()) return;

        String baseName = videoFile.getAbsolutePath().replaceAll("\\.(mp4|mkv|avi)$", "");
        String outputPath = baseName + ".mkv";

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(videoFile.getAbsolutePath());

            for (File sf : subFiles) {
                cmd.add("-i");
                cmd.add(sf.getAbsolutePath());
            }

            cmd.add("-map"); cmd.add("0:v");
            cmd.add("-map"); cmd.add("0:a");

            for (int i = 0; i < subFiles.size(); i++) {
                cmd.add("-map");
                cmd.add(String.valueOf(i + 1));
            }

            cmd.add("-c:v"); cmd.add("copy");
            cmd.add("-c:a"); cmd.add("copy");
            cmd.add("-c:s"); cmd.add("srt");

            for (int i = 0; i < subFiles.size(); i++) {
                File sf = subFiles.get(i);
                String label = sf.getName().replace(videoFile.getName().replaceAll("\\.(mp4|mkv|avi)$", ""), "");
                label = label.replaceAll("^\\.+", "").replaceAll("\\.[^.]+$", "");
                if (label.isEmpty()) label = "Sous-titres " + (i + 1);

                String lang = label.toLowerCase().contains("fr") ? "fre" : "und";

                cmd.add("-metadata:s:s:" + i);
                cmd.add("language=" + lang);
                cmd.add("-metadata:s:s:" + i);
                cmd.add("title=" + label);
            }

            cmd.add(outputPath);

            log.info("Muxing FFmpeg avec commande : {}", cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            }
            int exit = p.waitFor();
            if (exit == 0) {
                File out = new File(outputPath);
                if (out.exists() && out.length() > 0) {
                    task.setFileName(out.getName());
                    task.setSavePath(out.getAbsolutePath());
                    task.setSavePathSubtitle(null);
                    videoFile.delete();
                    for (File sf : subFiles) sf.delete();
                    persistTask(task);
                    log.info("Muxing FFmpeg réussi: {}", out.getName());
                }
            }
        } catch (Exception e) { log.error("Erreur FFmpeg: {}", e.getMessage()); }
    }

    public boolean isFFmpegAvailable() {
        try {
            String cmd = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            Process p = Runtime.getRuntime().exec(new String[]{cmd, "ffmpeg"});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    // =========================================================================
    // Queue
    // =========================================================================

    public synchronized void processQueue() {
        long active = tasks.values().stream()
                .filter(t -> t.getStatus() == DownloadStatus.DOWNLOADING || t.getStatus() == DownloadStatus.RETRYING)
                .count();

        if (active < maxConcurrentDownloads) {
            List<DownloadTask> pending = new ArrayList<>();
            for (String id : taskOrder) {
                DownloadTask t = tasks.get(id);
                if (t != null && t.getStatus() == DownloadStatus.PENDING) pending.add(t);
            }
            // Retrait du tri par date de création afin de respecter l'ordre personnalisé de taskOrder
            // pending.sort(Comparator.comparing(DownloadTask::getCreatedAt));

            int slots = maxConcurrentDownloads - (int) active;
            for (int i = 0; i < Math.min(slots, pending.size()); i++) {
                DownloadTask t = pending.get(i);
                t.setStatus(DownloadStatus.DOWNLOADING);
                broadcastUpdate(t);
                downloadExecutor.execute(() -> executeDownload(t, t.getSubtitleUrl()));
            }
        }
    }

    public synchronized void moveTaskToTop(String taskId) {
        if (taskOrder.contains(taskId)) {
            taskOrder.remove(taskId);
            taskOrder.add(0, taskId);
            processQueue();
            DownloadTask t = tasks.get(taskId);
            if (t != null) broadcastUpdate(t);
        }
    }

    public synchronized void moveSeriesToTop(String animeName) {
        if (animeName == null || animeName.isBlank()) return;
        List<String> seriesTaskIds = new ArrayList<>();
        for (String id : taskOrder) {
            DownloadTask t = tasks.get(id);
            if (t != null && animeName.equalsIgnoreCase(t.getAnimeName())) {
                seriesTaskIds.add(id);
            }
        }

        if (!seriesTaskIds.isEmpty()) {
            taskOrder.removeAll(seriesTaskIds);
            taskOrder.addAll(0, seriesTaskIds);
            processQueue();
            for (String id : seriesTaskIds) {
                DownloadTask t = tasks.get(id);
                if (t != null) broadcastUpdate(t);
            }
        }
    }

    public synchronized void reorderTasks(List<String> orderedIds) {
        if (orderedIds == null) return;
        List<String> newOrder = new ArrayList<>();
        for (String id : orderedIds) {
            if (tasks.containsKey(id)) {
                newOrder.add(id);
            }
        }
        for (String id : taskOrder) {
            if (!newOrder.contains(id)) {
                newOrder.add(id);
            }
        }
        taskOrder.clear();
        taskOrder.addAll(newOrder);
        processQueue();
    }

    // =========================================================================
    // SSE broadcast
    // =========================================================================

    private void broadcastUpdate(DownloadTask task) {
        Map<String, Object> data = taskToMap(task);
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : sseEmitters) {
            try { emitter.send(SseEmitter.event().name("download-update").data(data)); }
            catch (Exception e) { dead.add(emitter); }
        }
        sseEmitters.removeAll(dead);
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

    // =========================================================================
    // Helpers
    // =========================================================================

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int u = 0; double size = bytes;
        while (size >= 1024 && u < units.length - 1) { size /= 1024; u++; }
        return String.format(Locale.US, "%.1f %s", size, units[u]);
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "download";
        return name.replaceAll("[<>:\"/\\\\|?*]", "_").replaceAll("\\s+", " ").trim();
    }

    private List<String> parseJsonArray(String json) {
        List<String> list = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            for (JsonNode node : mapper.readTree(json)) list.add(node.asText());
        } catch (Exception ignored) {}
        return list;
    }

    // Getters pour les settings
    public Map<String, Object> getSettings() {
        return Map.of("maxConcurrentDownloads", maxConcurrentDownloads, "globalSpeedLimit", globalSpeedLimit);
    }

    private void generateNfoFiles(DownloadTask task) {
        try {
            File videoFile = new File(task.getSavePath());
            File seasonDir = videoFile.getParentFile();
            if (seasonDir == null || !seasonDir.exists()) return;
            File animeDir = seasonDir.getParentFile();
            if (animeDir == null || !animeDir.exists()) return;

            File tvshowNfo = new File(animeDir, "tvshow.nfo");
            if (!tvshowNfo.exists()) {
                String synopsis = "";
                String studio = "";
                List<String> genres = new ArrayList<>();
                
                com.pauldev.animan.model.FavoriteAnime favorite = null;
                try {
                    favorite = libraryService.getAllFavorites().stream()
                            .filter(f -> f.getTitle().equalsIgnoreCase(task.getAnimeName()))
                            .findFirst().orElse(null);
                } catch (Exception ignored) {}
                
                com.pauldev.animan.model.AnimeDetail detail = null;
                if (favorite != null) {
                    try {
                        detail = favorite.getUrl().contains("voir-anime")
                                ? voirAnimeService.getAnimeDetail(favorite.getUrl())
                                : scrapingService.getAnimeDetail(favorite.getUrl());
                    } catch (Exception ignored) {}
                }
                
                if (detail == null) {
                    try {
                        List<com.pauldev.animan.model.AnimeResult> results = scrapingService.searchAnime(task.getAnimeName());
                        if (results.isEmpty()) {
                            results = voirAnimeService.searchAnime(task.getAnimeName());
                        }
                        if (!results.isEmpty()) {
                            String url = results.get(0).getUrl();
                            detail = url.contains("voir-anime")
                                    ? voirAnimeService.getAnimeDetail(url)
                                    : scrapingService.getAnimeDetail(url);
                        }
                    } catch (Exception ignored) {}
                }
                
                if (detail != null) {
                    synopsis = detail.getSynopsis() != null ? detail.getSynopsis() : "";
                    studio = detail.getStudio() != null ? detail.getStudio() : "";
                    genres = detail.getGenres() != null ? detail.getGenres() : new ArrayList<>();
                }
                
                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tvshowNfo), "UTF-8"))) {
                    writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>");
                    writer.println("<tvshow>");
                    writer.println("    <title>" + escapeXml(task.getAnimeName()) + "</title>");
                    writer.println("    <plot>" + escapeXml(synopsis) + "</plot>");
                    writer.println("    <studio>" + escapeXml(studio) + "</studio>");
                    for (String genre : genres) {
                        writer.println("    <genre>" + escapeXml(genre) + "</genre>");
                    }
                    writer.println("</tvshow>");
                }
                log.info("Généré tvshow.nfo pour {}", task.getAnimeName());
            }
            
            String episodeNfoName = videoFile.getName().replaceAll("\\.(mp4|mkv|avi)$", "") + ".nfo";
            File episodeNfo = new File(seasonDir, episodeNfoName);
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(episodeNfo), "UTF-8"))) {
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>");
                writer.println("<episodedetails>");
                writer.println("    <title>" + escapeXml("Épisode " + task.getEpisodeNumber()) + "</title>");
                writer.println("    <episode>" + task.getEpisodeNumber() + "</episode>");
                writer.println("    <season>1</season>");
                writer.println("    <plot>" + escapeXml(task.getAnimeName() + " - Épisode " + task.getEpisodeNumber()) + "</plot>");
                writer.println("</episodedetails>");
            }
            log.info("Généré .nfo d'épisode pour {}", videoFile.getName());
            
        } catch (Exception e) {
            log.error("Erreur lors de la génération des fichiers .nfo: {}", e.getMessage());
        }
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
