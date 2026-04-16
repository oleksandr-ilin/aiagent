
# Introduction to RAG

[Retrieval-Augmented Generation (RAG)](https://aws.amazon.com/what-is/retrieval-augmented-generation/)
enhances AI models by grounding responses in external data sources. Instead of relying solely on training data, the model retrieves relevant information from a knowledge base before generating responses.

The RAG flow works as follows:

1. Documents are split into chunks and converted to [vector embeddings](https://aws.amazon.com/what-is/embeddings-in-machine-learning/) 
2. Embeddings are stored in a [vector database](https://aws.amazon.com/what-is/vector-databases/) 
3. When a user asks a question, it's converted to an embedding
4. Similar document chunks are retrieved via similarity search
5. Retrieved chunks are added to the prompt as context
6. The model generates a response grounded in the retrieved information

Spring AI provides the [QuestionAnswerAdvisor](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
to handle this automatically. It intercepts requests, performs similarity search, and augments the prompt with relevant context.

For vector storage, Spring AI supports [multiple backends](https://docs.spring.io/spring-ai/reference/api/vectordbs.html):

| Vector Store |	Use case	| Infrastructure |
|--------------|----------------|----------------|
| [PgVectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)           | Apps using PostgreSQL     | PostgreSQL with pgvector extension |
| [OpenSearchVectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/opensearch.html) | Full-text + vector search | Amazon OpenSearch                  |
| [RedisVectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)           | Low-latency caching       | Redis with vector search           |
