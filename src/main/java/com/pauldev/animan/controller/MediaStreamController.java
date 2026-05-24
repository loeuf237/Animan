package com.pauldev.animan.controller;

import com.pauldev.animan.model.DownloadTask;
import com.pauldev.animan.service.DownloadManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Contrôleur permettant de lire les vidéos locales de manière fluide dans le navigateur.
 * Prend en charge les requêtes partielles HTTP 206 (Range) indispensables pour naviguer (seek) dans la vidéo.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MediaStreamController {

    private final DownloadManagerService downloadManager;

    /**
     * Diffuse un fichier vidéo local avec support complet du HTTP Range.
     */
    @GetMapping("/api/media/stream/{id}")
    public ResponseEntity<StreamingResponseBody> streamVideo(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        DownloadTask task = downloadManager.getTask(id);
        if (task == null || task.getSavePath() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        File file = new File(task.getSavePath());
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long fileLength = file.length();
        // Support MKV et MP4
        String contentType = task.getFileName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";

        // Si aucun en-tête Range n'est fourni, renvoyer le fichier complet
        if (rangeHeader == null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(fileLength)
                    .body(outputStream -> {
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = raf.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, read);
                            }
                        }
                    });
        }

        // Parse l'en-tête Range (ex: "bytes=0-1024" ou "bytes=2048-")
        try {
            String[] ranges = rangeHeader.replace("bytes=", "").split("-");
            long rangeStart = Long.parseLong(ranges[0]);
            long rangeEnd = fileLength - 1;
            if (ranges.length > 1 && !ranges[1].isEmpty()) {
                rangeEnd = Long.parseLong(ranges[1]);
            }

            // Validation de la plage demandée
            if (rangeStart >= fileLength || rangeEnd >= fileLength || rangeStart > rangeEnd) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength)
                        .build();
            }

            long contentLength = rangeEnd - rangeStart + 1;
            final long start = rangeStart;
            final long end = rangeEnd;

            StreamingResponseBody responseBody = outputStream -> {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(start);
                    byte[] buffer = new byte[8192];
                    long bytesRemaining = contentLength;
                    int read;
                    while (bytesRemaining > 0 && (read = raf.read(buffer, 0, (int) Math.min(buffer.length, bytesRemaining))) != -1) {
                        outputStream.write(buffer, 0, read);
                        bytesRemaining -= read;
                    }
                }
            };

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength)
                    .header("Accept-Ranges", "bytes")
                    .contentLength(contentLength)
                    .body(responseBody);

        } catch (Exception e) {
            log.error("Erreur lors de la lecture partielle : {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Diffuse le fichier de sous-titres associé à une tâche (VTT).
     */
    @GetMapping("/api/media/subtitle/{id}")
    public ResponseEntity<org.springframework.core.io.FileSystemResource> streamSubtitle(@PathVariable String id) {
        DownloadTask task = downloadManager.getTask(id);
        if (task == null || task.getSavePathSubtitle() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        File file = new File(task.getSavePathSubtitle());
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/vtt"))
                .body(new org.springframework.core.io.FileSystemResource(file));
    }
}
