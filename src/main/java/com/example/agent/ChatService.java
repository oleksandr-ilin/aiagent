package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import javax.sql.DataSource;

@Service
public class ChatService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
        You are a helpful AI assistant for Unicorn Rentals, a fictional company that rents unicorns.
        Be friendly, helpful, and concise in your responses.
        If you don't have information, say I don't know, don't think up.
        """;

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder chatClientBuilder, DataSource dataSource) {

        var chatMemoryRepository = JdbcChatMemoryRepository.builder()
            .dataSource(dataSource)
            .dialect(new PostgresChatMemoryRepositoryDialect())
            .build();

        var chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(20)
            .build();

        this.chatClient = chatClientBuilder
            .defaultSystem(DEFAULT_SYSTEM_PROMPT)
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }

    public Flux<String> chat(String prompt, String username) {
        return chatClient.prompt().user(prompt)
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, username))
            .stream().content();
    }
}