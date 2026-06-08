package com.pauldev.animan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class ProxyRotatorService {

    @Value("${animan.proxies:}")
    private String proxiesConfig;

    private final List<Proxy> proxies = new ArrayList<>();
    
    private static final List<String> USER_AGENTS = Arrays.asList(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0"
    );

    private String selectedUserAgent = "Random (Rotator)";

    public String getSelectedUserAgent() {
        return selectedUserAgent;
    }

    public void setSelectedUserAgent(String selectedUserAgent) {
        if (selectedUserAgent != null && !selectedUserAgent.isBlank()) {
            this.selectedUserAgent = selectedUserAgent;
        } else {
            this.selectedUserAgent = "Random (Rotator)";
        }
    }

    public String getUserAgent() {
        if (selectedUserAgent == null || selectedUserAgent.isBlank() || "Random (Rotator)".equalsIgnoreCase(selectedUserAgent)) {
            return getRandomUserAgent();
        }
        if ("Chrome".equalsIgnoreCase(selectedUserAgent)) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
        }
        if ("Firefox".equalsIgnoreCase(selectedUserAgent)) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0";
        }
        if ("Safari".equalsIgnoreCase(selectedUserAgent)) {
            return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15";
        }
        if ("Edge".equalsIgnoreCase(selectedUserAgent)) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0";
        }
        return selectedUserAgent;
    }

    @PostConstruct
    public void init() {
        // 1. Lire de application.properties
        if (proxiesConfig != null && !proxiesConfig.isBlank()) {
            for (String pStr : proxiesConfig.split(",")) {
                addProxyStr(pStr);
            }
        }
        
        // 2. Lire de proxies.txt
        File txtFile = new File("proxies.txt");
        if (txtFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(txtFile))) {
                String line;
                while ((line = r.readLine()) != null) {
                    addProxyStr(line);
                }
            } catch (IOException e) {
                log.warn("Erreur lecture proxies.txt: {}", e.getMessage());
            }
        }
        
        if (!proxies.isEmpty()) {
            log.info("{} proxy(s) chargé(s) au total.", proxies.size());
        }
    }

    private void addProxyStr(String pStr) {
        pStr = pStr.trim();
        if (pStr.isEmpty() || pStr.startsWith("#")) return;
        try {
            String[] parts = pStr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            proxies.add(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
            log.info("Proxy enregistré: {}:{}", host, port);
        } catch (Exception e) {
            log.warn("Proxy invalide ignoré: {}", pStr);
        }
    }

    public Proxy getRandomProxy() {
        if (proxies.isEmpty()) return null;
        return proxies.get(ThreadLocalRandom.current().nextInt(proxies.size()));
    }

    public String getRandomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }
}
