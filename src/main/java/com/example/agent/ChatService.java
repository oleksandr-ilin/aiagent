package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
            .build();
    }

    public Flux<String> chat(String prompt, String username) {
        return chatClient.prompt().user(prompt)
            .stream().content();
    }
}
