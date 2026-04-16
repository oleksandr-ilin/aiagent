package com.example.agent;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@CrossOrigin(origins = "*")
public class InvocationController {
    private final ChatService chatService;

    public InvocationController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "invocations", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> handleInvocation(
            @RequestBody InvocationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return chatService.chat(request.prompt(), "default");
        }
        String visitorId = jwt.getSubject().replace("-", "").substring(0, 25);
        String sessionId = jwt.getClaim("auth_time").toString();
        return chatService.chat(request.prompt(), visitorId + ":" + sessionId);
    }

    @PostMapping(value = "load", consumes = MediaType.TEXT_PLAIN_VALUE)
    public void loadDocument(@RequestBody String content) {
        chatService.loadDocument(content);
    }
}