package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class InvocationService {
    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InvocationService(ChatService chatService) {
        this.chatService = chatService;
    }

    @AgentCoreInvocation
    public Flux<String> handleInvocation(InvocationRequest request, AgentCoreContext context) throws Exception {
        String jwt = context.getHeader(AgentCoreHeaders.AUTHORIZATION).replace("Bearer ", "");
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        JsonNode claims = objectMapper.readTree(payload);
        String visitorId = claims.get("sub").asText().replace("-", "").substring(0, 25);
        String authTime = claims.get("auth_time").asText();
        String sessionId = visitorId + ":" + authTime;
        return chatService.chat(request.prompt(), sessionId);
    }
}
