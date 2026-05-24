package com.pauldev.animan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Représente une tâche de téléchargement avec suivi de progression.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTask {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    private String animeName;
    private int episodeNumber;
    private String fileName;
    private String fileUrl;
    private String savePath;

    @Builder.Default
    private DownloadStatus status = DownloadStatus.PENDING;

    @Builder.Default
    private double progress = 0.0;

    private long downloadedBytes;
    private long totalBytes;
    private long speed;          // bytes/sec
    private String error;

    private long maxSpeedLimit; // bytes/sec, 0 if unlimited
    private LocalDateTime scheduledStartTime;
    private boolean supportsResume;
    private int retryCount;
    private String subtitleUrl;
    private String savePathSubtitle;

    private String downloadPageUrl;
    private String directDownloadUrl;
    @Builder.Default
    private boolean downloadSubtitles = false;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    // Marqueur transient pour l'annulation
    @Builder.Default
    private transient volatile boolean cancelled = false;

    // Marqueur transient pour la pause
    @Builder.Default
    private transient volatile boolean paused = false;

    public enum DownloadStatus {
        PENDING,
        SCHEDULED,
        DOWNLOADING,
        RETRYING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Formate la taille en unités lisibles (KB, MB, GB).
     */
    public String getFormattedTotalSize() {
        if (totalBytes <= 0) {
            return "Taille inconnue";
        }
        return formatBytes(totalBytes);
    }

    public String getFormattedDownloadedSize() {
        return formatBytes(downloadedBytes);
    }

    public String getFormattedSpeed() {
        if (speed <= 0) return "0 B/s";
        return formatBytes(speed) + "/s";
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }
}
