package com.pauldev.animan.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Entité JPA persistée en base H2.
 * Représente l'historique complet de chaque tâche de téléchargement.
 *
 * Utilise name="DownloadRecord" pour éviter tout conflit avec une éventuelle
 * entité legacy com.pauldev.animan.entity.DownloadTaskEntity.
 */
@Entity(name = "DownloadRecord")
@Table(name = "download_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTaskEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(nullable = false)
    private String animeName;

    private int episodeNumber;

    @Column(nullable = false)
    private String fileName;

    private String fileUrl;
    private String savePath;
    private String savePathSubtitle;
    private String downloadPageUrl;
    private String directDownloadUrl;
    private String subtitleUrl;

    @Column(length = 2000)
    private String subtitlesJson;

    @Column(length = 1000)
    private String error;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DownloadTask.DownloadStatus status = DownloadTask.DownloadStatus.PENDING;

    @Builder.Default
    private double progress = 0.0;
    private long downloadedBytes;
    private long totalBytes;
    private long maxSpeedLimit;
    private int retryCount;

    @Builder.Default
    private boolean downloadSubtitles = false;

    private LocalDateTime scheduledStartTime;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    /** Source : french-manga ou voir-anime */
    @Builder.Default
    private String source = "french-manga";

    // ---- Conversion depuis DownloadTask (modèle mémoire) ----

    public static DownloadTaskEntity fromTask(DownloadTask task) {
        return DownloadTaskEntity.builder()
                .id(task.getId())
                .animeName(task.getAnimeName())
                .episodeNumber(task.getEpisodeNumber())
                .fileName(task.getFileName())
                .fileUrl(task.getFileUrl())
                .savePath(task.getSavePath())
                .savePathSubtitle(task.getSavePathSubtitle())
                .downloadPageUrl(task.getDownloadPageUrl())
                .directDownloadUrl(task.getDirectDownloadUrl())
                .subtitleUrl(task.getSubtitleUrl())
                .subtitlesJson(task.getSubtitlesJson())
                .error(task.getError())
                .status(task.getStatus())
                .progress(task.getProgress())
                .downloadedBytes(task.getDownloadedBytes())
                .totalBytes(task.getTotalBytes())
                .maxSpeedLimit(task.getMaxSpeedLimit())
                .retryCount(task.getRetryCount())
                .downloadSubtitles(task.isDownloadSubtitles())
                .scheduledStartTime(task.getScheduledStartTime())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    public DownloadTask toTask() {
        return DownloadTask.builder()
                .id(this.id)
                .animeName(this.animeName)
                .episodeNumber(this.episodeNumber)
                .fileName(this.fileName)
                .fileUrl(this.fileUrl)
                .savePath(this.savePath)
                .savePathSubtitle(this.savePathSubtitle)
                .downloadPageUrl(this.downloadPageUrl)
                .directDownloadUrl(this.directDownloadUrl)
                .subtitleUrl(this.subtitleUrl)
                .subtitlesJson(this.subtitlesJson)
                .error(this.error)
                .status(this.status)
                .progress(this.progress)
                .downloadedBytes(this.downloadedBytes)
                .totalBytes(this.totalBytes)
                .maxSpeedLimit(this.maxSpeedLimit)
                .retryCount(this.retryCount)
                .downloadSubtitles(this.downloadSubtitles)
                .scheduledStartTime(this.scheduledStartTime)
                .createdAt(this.createdAt)
                .completedAt(this.completedAt)
                .build();
    }
}
