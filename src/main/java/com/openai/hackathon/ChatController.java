package com.openai.hackathon;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Tag(name = "Chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        if (req == null || req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return new ChatResponse(chatService.chat(req.prompt()));
    }

    @PostMapping(value = "/chat-with-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Tag(name = "Chat")
    public ResponseEntity<ChatResponse> chatWithFile(
            @RequestPart("prompt") String prompt,
            @RequestPart("file") MultipartFile file
    ) {
        String answer = chatService.chatWithFile(prompt, file);
        return ResponseEntity.ok(new ChatResponse(answer));
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

    public record ChatResponse(String answer) {

    }
}
