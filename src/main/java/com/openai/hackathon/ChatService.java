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

import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.http.client.MultipartBodyBuilder;

import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final String SESSION_CONVERSATION_ID = "openai_conversation_id";

    private final WebClient webClient;
    private final ObjectMapper om = new ObjectMapper();

    private final String model;
    private final String vectorStoreId;

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

        String conversationId = session == null ? null : (String) session.getAttribute(SESSION_CONVERSATION_ID);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = createConversation();
            if (session != null) {
                session.setAttribute(SESSION_CONVERSATION_ID, conversationId);
            }
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("conversation", conversationId);
        payload.put("input", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "input_text", "text", prompt))
                )
        ));

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

            String outputText = root.path("output_text").asText(null);
            if (outputText != null && !outputText.isBlank()) return outputText;

            return extractOutputText(root);

        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing OpenAI response JSON: " + e.getMessage(), e);
        }
    }

    public void addFileToVectorStore(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            throw new IllegalStateException("openai.vector-store-id is missing");
        }

        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("purpose", "assistants"); // for file_search / vector stores
        Resource resource = file.getResource();
        mb.part("file", resource)
                .filename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin");

        MultiValueMap<String, HttpEntity<?>> multipartBody = mb.build();

        String uploadRaw = webClient.post()
                .uri("/v1/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipartBody))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (uploadRaw == null || uploadRaw.isBlank()) {
            throw new IllegalStateException("Empty response from OpenAI files upload");
        }

        final String fileId;
        try {
            fileId = om.readTree(uploadRaw).path("id").asText(null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing file upload JSON: " + e.getMessage(), e);
        }
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalStateException("OpenAI did not return a file id");
        }

        String attachRaw = webClient.post()
                .uri("/v1/vector_stores/{vectorStoreId}/files", vectorStoreId)
                .bodyValue(Map.of("file_id", fileId))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (attachRaw == null || attachRaw.isBlank()) {
            throw new IllegalStateException("Empty response from OpenAI vector store attach");
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
