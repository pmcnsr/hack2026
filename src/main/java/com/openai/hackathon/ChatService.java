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

    private static final String SESSION_CONVERSATION_ID = "openai_conversation_id";

    private final WebClient webClient;
    private final ObjectMapper om = new ObjectMapper();

    private final String model;
    private final String vectorStoreId; // can be blank/null

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

        this.vectorStoreId = props.vectorStoreId() == null ? "" : props.vectorStoreId().trim();

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey().trim())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String chat(String prompt) {
        HttpSession session = currentSession(true);

        // 1) Ensure we have a durable conversation id
        String conversationId = session == null ? null : (String) session.getAttribute(SESSION_CONVERSATION_ID);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = createConversation();
            if (session != null) {
                session.setAttribute(SESSION_CONVERSATION_ID, conversationId);
            }
        }

        // 2) Build /v1/responses payload (using conversation + file_search)
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("conversation", conversationId);
        payload.put("input", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "input_text", "text", prompt))
                )
        ));

        // Make the conversation aware of your uploaded files via vector store
        if (!vectorStoreId.isBlank()) {
            payload.put("tools", List.of(
                    Map.of(
                            "type", "file_search",
                            "vector_store_ids", List.of(vectorStoreId)
                    )
            ));
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

            // Fast path: output_text is usually present
            String outputText = root.path("output_text").asText(null);
            if (outputText != null && !outputText.isBlank()) return outputText;

            return extractOutputText(root);

        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing OpenAI response JSON: " + e.getMessage(), e);
        }
    }

    private String createConversation() {
        // POST /v1/conversations -> returns { id: "conv_..." }
        String raw = webClient.post()
                .uri("/v1/conversations")
                .bodyValue(Map.of()) // no items initially
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty response from OpenAI when creating conversation");
        }

        try {
            JsonNode root = om.readTree(raw);
            String id = root.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("No conversation id returned by OpenAI");
            }
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing conversation create JSON: " + e.getMessage(), e);
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
