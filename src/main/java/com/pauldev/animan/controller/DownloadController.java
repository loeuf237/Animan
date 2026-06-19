package com.pauldev.animan.controller;

import com.pauldev.animan.model.DownloadTask;
import com.pauldev.animan.service.DownloadManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur pour la gestion des téléchargements.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadManagerService downloadManager;

    /**
     * Page du gestionnaire de téléchargements.
     */
    @GetMapping("/downloads")
    public String downloadsPage(Model model) {
        model.addAttribute("tasks", downloadManager.getAllTasks());
        return "downloads";
    }

    /**
     * Démarre un téléchargement unique.
     */
    @PostMapping("/api/download")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startDownload(@RequestBody Map<String, String> body) {
        String animeName = body.getOrDefault("animeName", "Unknown");
        int episodeNumber = Integer.parseInt(body.getOrDefault("episodeNumber", "0"));
        String url = body.getOrDefault("url", "");
        String fileName = body.getOrDefault("fileName",
                animeName + " - Episode " + episodeNumber + ".mp4");
        String subtitleUrl = body.getOrDefault("subtitleUrl", "");
        boolean downloadSubtitles = Boolean.parseBoolean(body.getOrDefault("downloadSubtitles", "false"));
        
        // Paramètres avancés
        String scheduledTimeStr = body.get("scheduledTime");
        LocalDateTime scheduledTime = null;
        if (scheduledTimeStr != null && !scheduledTimeStr.isBlank()) {
            try {
                scheduledTime = LocalDateTime.parse(scheduledTimeStr);
            } catch (Exception e) {
                log.warn("Date de planification invalide: {}", scheduledTimeStr);
            }
        }

        long speedLimit = 0;
        String speedLimitStr = body.get("speedLimit");
        if (speedLimitStr != null && !speedLimitStr.isBlank()) {
            try {
                speedLimit = Long.parseLong(speedLimitStr);
            } catch (Exception e) {
                log.warn("Limite de vitesse individuelle invalide: {}", speedLimitStr);
            }
        }

        if (url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL manquante"));
        }

        // Vérification de doublon avec taille correspondante
        java.io.File existingFile = downloadManager.checkExistingFile(animeName, fileName, episodeNumber);
        if (existingFile != null) {
            long localSize = existingFile.length();
            DownloadManagerService.ResolvedVideoInfo info = downloadManager.getOrResolveEpisodeInfo(url);
            if (info != null && info.size > 0) {
                if (localSize == info.size) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "alreadyExists", true,
                            "error", "L'épisode existe déjà sur le disque avec la même taille (" + info.formattedSize + ")."
                    ));
                }
            }
        }

        DownloadTask task = downloadManager.startDownload(animeName, episodeNumber, url, fileName, subtitleUrl, scheduledTime, speedLimit, downloadSubtitles);

        String msg = task.getStatus() == DownloadTask.DownloadStatus.SCHEDULED ? 
                "Téléchargement planifié pour : " + scheduledTimeStr : 
                "Téléchargement démarré: " + task.getFileName();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "taskId", task.getId(),
                "message", msg
        ));
    }

    /**
     * Définit la limite globale de vitesse de téléchargement.
     */
    @PostMapping("/api/download/limit-speed")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setGlobalSpeedLimit(@RequestBody Map<String, Object> body) {
        try {
            long limit = Long.parseLong(String.valueOf(body.get("limit")));
            downloadManager.setGlobalSpeedLimit(limit);
            return ResponseEntity.ok(Map.of("success", true, "message", "Vitesse globale mise à jour"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Format de limite invalide : " + e.getMessage()));
        }
    }

    /**
     * Démarre le téléchargement de plusieurs épisodes.
     */
    @PostMapping("/api/download/batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startBatchDownload(@RequestBody Map<String, Object> body) {
        String animeName = (String) body.getOrDefault("animeName", "Unknown");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> episodes = (List<Map<String, String>>) body.get("episodes");

        if (episodes == null || episodes.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aucun épisode sélectionné"));
        }

        List<DownloadTask> tasks = downloadManager.startBatchDownload(animeName, episodes);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", tasks.size(),
                "message", tasks.size() + " téléchargement(s) démarré(s)"
        ));
    }

    /**
     * Annule un téléchargement.
     */
    @DeleteMapping("/api/download/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> cancelDownload(@PathVariable String id) {
        boolean cancelled = downloadManager.cancelDownload(id);
        if (cancelled) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Téléchargement annulé"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Impossible d'annuler ce téléchargement"));
    }

    /**
     * Met en pause un téléchargement.
     */
    @PostMapping("/api/download/{id}/pause")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pauseDownload(@PathVariable String id) {
        boolean paused = downloadManager.pauseDownload(id);
        if (paused) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Téléchargement mis en pause"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Impossible de mettre en pause ce téléchargement"));
    }

    /**
     * Reprend un téléchargement.
     */
    @PostMapping("/api/download/{id}/resume")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resumeDownload(@PathVariable String id) {
        boolean resumed = downloadManager.resumeDownload(id);
        if (resumed) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Téléchargement repris"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Impossible de reprendre ce téléchargement"));
    }

    /**
     * Supprime une tâche de la liste.
     */
    @DeleteMapping("/api/download/{id}/remove")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> removeTask(@PathVariable String id) {
        boolean removed = downloadManager.removeTask(id);
        if (removed) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Tâche supprimée"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Tâche introuvable"));
    }

    /**
     * SSE endpoint pour recevoir les mises à jour de progression en temps réel.
     */
    @GetMapping(value = "/api/download/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamDownloadUpdates() {
        return downloadManager.createSseEmitter();
    }

    /**
     * Retourne l'état de toutes les tâches (API JSON).
     */
    @GetMapping("/api/download/status")
    @ResponseBody
    public List<DownloadTask> getAllStatus() {
        return downloadManager.getAllTasks();
    }

    /**
     * Définit la limite de vitesse individuelle d'une tâche.
     */
    @PostMapping("/api/download/{id}/speed-limit")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setTaskSpeedLimit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            long limit = Long.parseLong(String.valueOf(body.get("limit")));
            boolean updated = downloadManager.setTaskSpeedLimit(id, limit);
            if (updated) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Limite de vitesse mise à jour"));
            }
            return ResponseEntity.badRequest().body(Map.of("error", "Tâche introuvable"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Format de limite invalide"));
        }
    }

    /**
     * Met en pause tous les téléchargements actifs.
     */
    @PostMapping("/api/download/pause-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pauseAllDownloads() {
        downloadManager.pauseAllDownloads();
        return ResponseEntity.ok(Map.of("success", true, "message", "Tous les téléchargements actifs ont été mis en pause"));
    }

    /**
     * Reprend tous les téléchargements en pause.
     */
    @PostMapping("/api/download/resume-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resumeAllDownloads() {
        downloadManager.resumeAllDownloads();
        return ResponseEntity.ok(Map.of("success", true, "message", "Tous les téléchargements en pause ont été repris"));
    }

    /**
     * Supprime toutes les tâches inactives de la liste.
     */
    @PostMapping("/api/download/clean")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearInactiveTasks() {
        downloadManager.clearInactiveTasks();
        return ResponseEntity.ok(Map.of("success", true, "message", "Tâches inactives nettoyées"));
    }

    /**
     * Retourne les paramètres de configuration actuels du téléchargeur.
     */
    @GetMapping("/api/download/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(Map.of(
                "maxConcurrentDownloads", downloadManager.getMaxConcurrentDownloads(),
                "globalSpeedLimit", downloadManager.getGlobalSpeedLimit(),
                "plexOrganization", downloadManager.isPlexOrganization(),
                "selectedUserAgent", downloadManager.getSelectedUserAgent(),
                "ffmpegAvailable", downloadManager.isFFmpegAvailable()
        ));
    }

    /**
     * Enregistre les nouveaux paramètres de configuration.
     */
    @PostMapping("/api/download/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        try {
            int maxConcurrent = Integer.parseInt(String.valueOf(body.get("maxConcurrentDownloads")));
            long globalLimit = Long.parseLong(String.valueOf(body.get("globalSpeedLimit")));
            boolean plexOrg = Boolean.parseBoolean(String.valueOf(body.get("plexOrganization")));
            String selectedUA = (String) body.get("selectedUserAgent");
            
            downloadManager.setMaxConcurrentDownloads(maxConcurrent);
            downloadManager.setGlobalSpeedLimit(globalLimit);
            downloadManager.setPlexOrganization(plexOrg);
            if (selectedUA != null) {
                downloadManager.setSelectedUserAgent(selectedUA);
            }
            
            return ResponseEntity.ok(Map.of("success", true, "message", "Paramètres mis à jour"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Paramètres invalides"));
        }
    }

    /**
     * Récupère asynchroniquement la taille et les métadonnées d'un épisode.
     */
    @GetMapping("/api/episode/size")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getEpisodeSize(@RequestParam String url) {
        try {
            DownloadManagerService.ResolvedVideoInfo info = downloadManager.getOrResolveEpisodeInfo(url);
            if (info != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "formattedSize", info.formattedSize,
                        "size", info.size,
                        "directUrl", info.directUrl != null ? info.directUrl : "",
                        "subtitleUrl", info.subtitleUrl != null ? info.subtitleUrl : ""
                ));
            }
            return ResponseEntity.ok(Map.of("success", false, "formattedSize", "Taille inconnue", "size", -1));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "formattedSize", "Erreur résolution", "size", -1));
        }
    }

    /**
     * Fait monter une tâche en haut de la file de téléchargement.
     */
    @PostMapping("/api/download/{id}/move-to-top")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> moveTaskToTop(@PathVariable String id) {
        downloadManager.moveTaskToTop(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Tâche montée en haut de la file"));
    }

    /**
     * Fait monter tous les épisodes d'une série en haut de la file de téléchargement.
     */
    @PostMapping("/api/download/move-series-to-top")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> moveSeriesToTop(@RequestBody Map<String, String> body) {
        String animeName = body.get("animeName");
        if (animeName == null || animeName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nom de la série manquant"));
        }
        downloadManager.moveSeriesToTop(animeName);
        return ResponseEntity.ok(Map.of("success", true, "message", "Série " + animeName + " montée en haut de la file"));
    }

    /**
     * Réordonne la file d'attente.
     */
    @PostMapping("/api/download/reorder")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reorderTasks(@RequestBody List<String> orderedIds) {
        downloadManager.reorderTasks(orderedIds);
        return ResponseEntity.ok(Map.of("success", true, "message", "File d'attente réorganisée"));
    }
}
