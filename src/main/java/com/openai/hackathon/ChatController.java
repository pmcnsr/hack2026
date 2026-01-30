package com.openai.hackathon;

import static com.openai.hackathon.ChatService.SESSION_CONVERSATION_ID;
import static com.openai.hackathon.ChatService.currentSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final LocalLlamaService localLlamaService;

    public ChatController(ChatService chatService, LocalLlamaService localLlamaService) {
        this.chatService = chatService;
        this.localLlamaService = localLlamaService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    @Tag(name = "Chat")
    public String chat(@RequestBody ChatRequest req) {
        if (req == null || req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return chatService.chat(req.prompt());
    }

    @PostMapping(value = "/chat/context", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    @Tag(name = "Chat")
    public ResponseEntity<String> chatWithFile(
            @RequestPart("prompt") String prompt,
            @RequestPart("file") MultipartFile file
    ) {
        String answer = chatService.chatWithFile(prompt, file);
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/chat/reset")
    @Tag(name = "Chat Control")
    public ResponseEntity<Void> resetConversation() {
        chatService.resetChat();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chat/status")
    @Tag(name = "Chat Control")
    public Map<String, Object> getConversationInfo() {
        HttpSession session = currentSession(false);

        Map<String, Object> result = new HashMap<>();
        Object conversationId = session == null ? null : session.getAttribute(SESSION_CONVERSATION_ID);

        result.put("conversationId", conversationId);
        result.put("active", conversationId != null);

        return result;
    }

    @PostMapping(
            value = "/chat/local",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_HTML_VALUE
    )
    @Tag(name = "Chat local")
    public String chatLocal(@RequestBody ChatRequest req) {
        if (req == null || req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return localLlamaService.chat(req.prompt());
    }

    @GetMapping(value = "/vector-store/files", produces = MediaType.APPLICATION_JSON_VALUE)
    @Tag(name = "Files")
    public List<FileInfo> listVectorStoreFiles() {
        return chatService.listVectorStoreFilesWithIds();
    }

    public record FileInfo(String fileId, String filename) {

    }

    @PostMapping(value = "/vector-store/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Tag(name = "Files")
    public ResponseEntity<String> addFileToVectorStore(@RequestPart("file") MultipartFile file) {
        var id = chatService.addFileToVectorStore(file);
        return ResponseEntity.ok().body("{\"fileId\":" + id);
    }

    @DeleteMapping(value = "/vector-store/files/{fileId}")
    @Tag(name = "Files")
    public ResponseEntity<Void> deleteVectorStoreFile(@PathVariable String fileId) {
        chatService.removeFileFromVectorStore(fileId);
        return ResponseEntity.noContent().build(); // 204
    }

    public record ChatRequest(String prompt) {

    }
}
