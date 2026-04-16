
# Introduction to conversation memory

AI models are stateless - each request is independent with no knowledge of previous interactions. To create conversational experiences, applications must manage conversation history and include it with each request.

Spring AI provides the [MessageChatMemoryAdvisor](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
to handle this automatically. It intercepts requests, retrieves conversation history, and appends it to the prompt.

Spring AI offers several storage options for conversation memory:

| Repository                    | Use case                                   | Persistence                   |
|-------------------------------|--------------------------------------------|-------------------------------|
| InMemoryChatMemoryRepository	| Development, testing, single-instance apps | No - lost on restart          |
| JdbcChatMemoryRepository	    | Apps already using relational databases.   | Yes - PostgreSQL, MySQL, etc. |
| CassandraChatMemoryRepository | High-scale distributed systems             | Yes - Apache Cassandra        |
| Neo4jChatMemoryRepository	    | Graph-based applications                   | Yes - Neo4j                   |

This workshop uses JdbcChatMemoryRepository with PostgreSQL because most enterprise applications already have a relational database, minimizing additional infrastructure. Amazon Aurora Serverless 
was created during setup.