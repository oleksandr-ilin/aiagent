# Introduction to system prompt

A system prompt defines the AI Agent's persona, behavior, and constraints. It is sent with every request and shapes how the model responds.

System prompts can:

- Define the agent's role and personality
- Set boundaries on what the agent should or shouldn't do
- Provide context about the business domain
- Specify response format and tone

In Spring AI, the system prompt is configured via ChatClient.Builder.defaultSystem().