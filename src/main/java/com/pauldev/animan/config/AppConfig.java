package com.pauldev.animan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

    @Value("${animan.download-dir}")
    private String downloadDir;

    @Value("${animan.max-concurrent-downloads}")
    private int maxConcurrentDownloads;

    /**
     * Pool de threads dédié aux téléchargements.
     */
    @Bean(name = "downloadExecutor")
    public Executor downloadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15);
        executor.setMaxPoolSize(25);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("download-");
        executor.initialize();
        return executor;
    }

    /**
     * Crée le répertoire de téléchargement au démarrage.
     */
    @Bean
    public File downloadDirectory() {
        File dir = new File(downloadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
