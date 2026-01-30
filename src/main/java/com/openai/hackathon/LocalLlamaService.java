package com.openai.hackathon;

import static com.openai.hackathon.Constants.DEV_PROMPT;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class LocalLlamaService {

    private static final List<Map<String, String>> HISTORY = new ArrayList<>();

    private final WebClient webui;
    private final ObjectMapper om = new ObjectMapper();
    private final LocalAIProps props;

    public LocalLlamaService(LocalAIProps props) {
        this.props = props;

        synchronized (HISTORY) {
            if (HISTORY.isEmpty()) {
                HISTORY.add(msg("system", DEV_PROMPT));
            }
        }

        this.webui = WebClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token())
                .build();
    }

    public String chat(String userInput) {

        List<Map<String, String>> snapshot;
        synchronized (HISTORY) {
            HISTORY.add(msg("user", userInput));
            snapshot = List.copyOf(HISTORY);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.model());
        payload.put("stream", false);
        payload.put("messages", snapshot);

        String raw = webui.post()
                .uri("/api/chat/completions")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty response from OpenWebUI");
        }

        String answer;
        try {
            JsonNode root = om.readTree(raw);
            answer = root.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenWebUI response", e);
        }

        synchronized (HISTORY) {
            HISTORY.add(msg("assistant", answer));
        }

        return answer;
    }

    private static Map<String, String> msg(String role, String content) {
        return Map.of("role", role, "content", content == null ? "" : content);
    }
}
