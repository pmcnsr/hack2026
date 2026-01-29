package com.openai.hackathon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final String SESSION_PREV_RESPONSE_ID = "prev_response_id";

    private final WebClient webClient;
    private final ObjectMapper om = new ObjectMapper();
    private final String model;

    public ChatService(OpenAIProps props) {
        if (props == null || props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("Missing OpenAI API key. Set openai.api-key (or env mapping to it).");
        }

        String baseUrl = (props.baseUrl() == null || props.baseUrl().isBlank())
                ? "https://api.openai.com"
                : props.baseUrl().trim();

        this.model = (props.model() == null || props.model().isBlank())
                ? "gpt-4.1-mini"
                : props.model().trim();

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey().trim())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String chat(String prompt) {
        HttpSession session = currentSession(true);
        String prev = session == null ? null : (String) session.getAttribute(SESSION_PREV_RESPONSE_ID);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", prompt)
                        )
                )
        ));
        if (prev != null && !prev.isBlank()) {
            payload.put("previous_response_id", prev);
        }

        String raw = webClient.post()
                .uri("/v1/responses")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (raw == null || raw.isBlank()) throw new IllegalStateException("Empty response from OpenAI");

        try {
            JsonNode root = om.readTree(raw);

            String responseId = root.path("id").asText(null);
            if (session != null && responseId != null && !responseId.isBlank()) {
                session.setAttribute(SESSION_PREV_RESPONSE_ID, responseId);
            }

            String outputText = root.path("output_text").asText(null);
            if (outputText != null && !outputText.isBlank()) return outputText;

            return extractOutputText(root);

        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing OpenAI response JSON: " + e.getMessage(), e);
        }
    }

    private static HttpSession currentSession(boolean create) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return null;
        HttpServletRequest request = sra.getRequest();
        return request.getSession(create);
    }

    private static String extractOutputText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        String t = c.path("text").asText(null);
                        if (t != null && !t.isBlank()) {
                            if (!sb.isEmpty()) sb.append('\n');
                            sb.append(t);
                        } else {
                            String t2 = c.path("text").path("value").asText(null);
                            if (t2 != null && !t2.isBlank()) {
                                if (!sb.isEmpty()) sb.append('\n');
                                sb.append(t2);
                            }
                        }
                    }
                }
            }
        }
        String s = sb.toString().trim();
        return s.isBlank() ? "(no text output)" : s;
    }
}
