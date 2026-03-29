package com.example.tsbot.player;

import com.example.tsbot.config.AutoDjProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Service
public class AutoDjService {

    private static final Logger log = LoggerFactory.getLogger(AutoDjService.class);
    private static final int HISTORY_SIZE = 20;

    private final AutoDjProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final LinkedList<String> playHistory = new LinkedList<>();
    private volatile boolean enabled;

    public AutoDjService(AutoDjProperties properties) {
        this.properties = properties;
        this.enabled = properties.isEnabled();
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public synchronized void recordPlay(String title) {
        playHistory.addFirst(title);
        while (playHistory.size() > HISTORY_SIZE) {
            playHistory.removeLast();
        }
        log.debug("AutoDJ: recorded play '{}', history size={}", title, playHistory.size());
    }

    public synchronized List<String> getPlayHistory() {
        return new ArrayList<>(playHistory);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return properties.getModel();
    }

    public List<String> suggestSongs() {
        List<String> history = getPlayHistory();

        if (history.isEmpty()) {
            log.warn("AutoDJ: no play history, cannot suggest songs");
            return Collections.emptyList();
        }

        String prompt = buildPrompt(history);

        try {
            String response = callOllama(prompt);
            log.info("AutoDJ: raw response from Ollama:\n{}", response);

            List<String> suggestions = parseResponse(response);

            // Filter out anything that closely matches recently played titles
            suggestions.removeIf(suggestion -> history.stream()
                    .anyMatch(played -> titlesMatch(played, suggestion)));

            log.info("AutoDJ: {} suggestions after filtering", suggestions.size());
            return suggestions;

        } catch (Exception e) {
            log.error("AutoDJ: failed to get suggestions from Ollama", e);
            return Collections.emptyList();
        }
    }

    private String buildPrompt(List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a music DJ assistant. Based on the recently played songs below, ");
        sb.append("suggest exactly ").append(properties.getSuggestCount());
        sb.append(" new songs to play next.\n\n");
        sb.append("Requirements:\n");
        sb.append("- Stay in the same genre, style, and vibe as the recently played songs\n");
        sb.append("- Do NOT suggest songs that are over 6 minuntes long\n");
        sb.append("- Do NOT suggest anything besides songs\n");
        sb.append("- Do NOT suggest any song that appears in the recently played list below\n");
        sb.append("- Choose songs that are popular enough to be found on YouTube\n");
        sb.append("Recently played songs (most recent first):\n");

        int limit = Math.min(history.size(), 15);
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(". ").append(history.get(i)).append("\n");
        }

        sb.append("\nRespond with ONLY the song suggestions, one per line, in this exact format:\n");
        sb.append("Artist - Song Title\n\n");
        sb.append("No numbering, no explanations, no extra text. Just the songs.");

        return sb.toString();
    }

    private String callOllama(String prompt) throws Exception {
        String url = properties.getOllamaUrl() + "/api/chat";

        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(message),
                "stream", false
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String jsonBody = objectMapper.writeValueAsString(body);
        HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

        log.info("AutoDJ: requesting suggestions from Ollama (model='{}', url='{}')",
                properties.getModel(), url);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, request, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("message").path("content").asText();
    }

    private List<String> parseResponse(String response) {
        List<String> songs = new ArrayList<>();

        for (String line : response.split("\n")) {
            String cleaned = line.trim();

            // Remove common prefixes: "1. ", "1) ", "- ", "* "
            cleaned = cleaned.replaceFirst("^\\d+[.)\\-]\\s*", "");
            cleaned = cleaned.replaceFirst("^[*\\-]\\s*", "");
            cleaned = cleaned.replaceAll("[\"']", "");
            cleaned = cleaned.trim();

            // Skip empty or too-short lines
            if (cleaned.length() > 3 && cleaned.contains("-")) {
                songs.add(cleaned);
            }
        }

        return songs;
    }

    static boolean titlesMatch(String a, String b) {
        String normA = normalize(a);
        String normB = normalize(b);
        return normA.contains(normB) || normB.contains(normA);
    }

    private static String normalize(String title) {
        return title.toLowerCase()
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("official\\s*(music\\s*)?video", "")
                .replaceAll("lyrics?\\s*video", "")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
