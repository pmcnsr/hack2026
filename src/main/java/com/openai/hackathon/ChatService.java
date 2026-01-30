package com.openai.hackathon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.hackathon.ChatController.FileInfo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public static final String SESSION_CONVERSATION_ID = "openai_conversation_id";
    private static final String DEV_PROMPT = """
            You are an assistant helping users analyze documents and answer questions.
            Prefer information from uploaded documents and file search results over general knowledge.
            Be concise and factual.
            If the answer is not contained in the provided context, say so clearly.
            If you give answers use HTML formatting and no emoticons.
            """;

    private String conversationId;

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

    public String chatWithFile(String prompt, MultipartFile file) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }

        HttpSession session = currentSession(true);

        // ensure conversation id exists (same approach as chat())
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = createConversation();
            if (session != null) session.setAttribute(SESSION_CONVERSATION_ID, conversationId);
        }

        // 1) Upload file -> file_id
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("purpose", "assistants");
        Resource resource = file.getResource();
        mb.part("file", resource)
                .filename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin");

        MultiValueMap<String, HttpEntity<?>> multipartBody = mb.build();

        final String uploadRaw;
        try {
            uploadRaw = webClient.post()
                    .uri("/v1/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(multipartBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            // This prints OpenAI's real error JSON + full stacktrace
            log.error(
                    "OpenAI /v1/files failed. status={} responseBody={}",
                    e.getStatusCode().value(),
                    safeBody(e),
                    e
            );
            throw e;
        }

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

        // 2) Call Responses API with input_file included in this turn
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (!vectorStoreId.isBlank()) {
            payload.put("tools", List.of(
                    Map.of(
                            "type", "file_search",
                            "vector_store_ids", List.of(vectorStoreId)
                    )
            ));
        }
        payload.put("model", model);
        payload.put("conversation", conversationId);
        payload.put("input", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", prompt),
                                Map.of("type", "input_file", "file_id", fileId)
                        )
                )
        ));

        final String raw;
        try {
            raw = webClient.post()
                    .uri("/v1/responses")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error(
                    "OpenAI /v1/responses failed. status={} responseBody={}. payload(model={}, conversationId={}, filename={}, size={})",
                    e.getStatusCode().value(),
                    safeBody(e),
                    model,
                    conversationId,
                    file.getOriginalFilename(),
                    file.getSize(),
                    e
            );
            throw e;
        }

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty response from OpenAI");
        }

        try {
            var root = om.readTree(raw);
            String outputText = root.path("output_text").asText(null);
            if (outputText != null && !outputText.isBlank()) return outputText;
            return extractOutputText(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing OpenAI response JSON: " + e.getMessage(), e);
        }
    }

    public void resetChat() {
        HttpSession session = currentSession(false);
        if (session != null) {
            session.removeAttribute("openai_conversation_id");
        }
    }

    private static String safeBody(WebClientResponseException e) {
        try {
            return e.getResponseBodyAsString(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "<unavailable>";
        }
    }

    public List<FileInfo> listVectorStoreFilesWithIds() {
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            throw new IllegalStateException("openai.vector-store-id is not configured");
        }

        final String listRaw;
        try {
            listRaw = webClient.get()
                    .uri("/v1/vector_stores/{id}/files", vectorStoreId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("OpenAI list vector store files failed. status={} body={}",
                    e.getStatusCode().value(), safeBody(e), e);
            throw e;
        }

        if (listRaw == null || listRaw.isBlank()) {
            return List.of();
        }

        List<FileInfo> result = new ArrayList<>();

        try {
            var root = om.readTree(listRaw);
            var data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }

            for (var item : data) {
                String fileId = item.path("id").asText(null);
                if (fileId == null || fileId.isBlank()) continue;

                try {
                    String fileRaw = webClient.get()
                            .uri("/v1/files/{id}", fileId)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

                    if (fileRaw == null || fileRaw.isBlank()) continue;

                    var fileJson = om.readTree(fileRaw);
                    String filename = fileJson.path("filename").asText(null);

                    if (filename != null && !filename.isBlank()) {
                        result.add(new FileInfo(fileId, filename));
                    }
                } catch (WebClientResponseException e) {
                    // hackathon mode: skip broken entries, continue
                    log.warn("Failed fetching file {} details. status={} body={}",
                            fileId, e.getStatusCode().value(), safeBody(e));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed parsing OpenAI vector store files response", e);
        }

        return result;
    }

    public void removeFileFromVectorStore(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            throw new IllegalStateException("openai.vector-store-id is not configured");
        }

        try {
            webClient.delete()
                    .uri("/v1/vector_stores/{vsId}/files/{fileId}", vectorStoreId, fileId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error(
                    "OpenAI delete vector store file failed. vectorStoreId={}, fileId={}, status={}, body={}",
                    vectorStoreId,
                    fileId,
                    e.getStatusCode().value(),
                    safeBody(e),
                    e
            );
            throw e;
        }
    }

    public String addFileToVectorStore(MultipartFile file) {
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
        System.out.println("Uploaded file ID: " + fileId);
        return fileId;
    }

    private String createConversation() {
        Map<String, Object> body = Map.of(
                "items", List.of(
                        Map.of(
                                "role", "developer",
                                "content", List.of(
                                        Map.of("type", "input_text", "text", DEV_PROMPT)
                                )
                        )
                )
        );
        final String raw;
        try {
            raw = webClient.post()
                    .uri("/v1/conversations")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("OpenAI /v1/conversations failed. status={} responseBody={}",
                    e.getStatusCode().value(),
                    safeBody(e),
                    e);
            throw e;
        }

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

    public static HttpSession currentSession(boolean create) {
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
