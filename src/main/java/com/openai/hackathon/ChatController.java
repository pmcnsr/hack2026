package com.openai.hackathon;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest req) {
        if (req == null || req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return new ChatResponse(chatService.chat(req.prompt()));
    }

    @PostMapping(value = "/chatWithFile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponse> chatWithFile(
            @RequestPart("prompt") String prompt,
            @RequestPart("file") MultipartFile file
    ) {
        String answer = chatService.chatWithFile(prompt, file);
        return ResponseEntity.ok(new ChatResponse(answer));
    }

    @PostMapping(value = "/vector-store/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> addFileToVectorStore(@RequestPart("file") MultipartFile file) {
        var id = chatService.addFileToVectorStore(file);
        return ResponseEntity.ok().body("{\"fileId\":" + id);
    }

    public record ChatRequest(String prompt) {

    }

    public record ChatResponse(String answer) {

    }
}
