package com.openai.hackathon;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

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

    public record ChatRequest(String prompt) {}
    public record ChatResponse(String answer) {}
}
