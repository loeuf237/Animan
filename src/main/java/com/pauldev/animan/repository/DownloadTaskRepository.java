package com.pauldev.animan.repository;

import com.pauldev.animan.model.DownloadTaskEntity;
import com.pauldev.animan.model.DownloadTask.DownloadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DownloadTaskRepository extends JpaRepository<DownloadTaskEntity, String> {
    List<DownloadTaskEntity> findAllByOrderByCreatedAtDesc();
    List<DownloadTaskEntity> findByStatus(DownloadStatus status);
    List<DownloadTaskEntity> findByAnimeName(String animeName);
    long countByStatus(DownloadStatus status);
    List<DownloadTaskEntity> findByStatusIn(java.util.Collection<DownloadStatus> statuses);
    void deleteByStatusIn(java.util.Collection<DownloadStatus> statuses);
    void deleteByAnimeName(String animeName);
}
