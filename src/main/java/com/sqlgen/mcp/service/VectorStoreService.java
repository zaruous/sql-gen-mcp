package com.sqlgen.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
// import dev.langchain4j.model.googleai.GeminiEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VectorStoreService {
    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);

    private final ObjectMapper objectMapper;
    private final Environment env;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;

    public VectorStoreService(ObjectMapper objectMapper, Environment env) {
        this.objectMapper = objectMapper;
        this.env = env;
    }

    @PostConstruct
    public void init() {
        String provider = env.getProperty("ai.vector-store.provider", "ollama");
        logger.info("Initializing VectorStore with provider: {}", provider);

        try {
            this.embeddingModel = createEmbeddingModel(provider);
            this.embeddingStore = new InMemoryEmbeddingStore<>();
            loadAndIndexDocs();
        } catch (Exception e) {
            logger.error("Failed to initialize VectorStore: {}", e.getMessage(), e);
        }
    }

    private EmbeddingModel createEmbeddingModel(String provider) {
        String prefix = "ai.vector-store.providers." + provider;
        
        switch (provider.toLowerCase()) {
            case "ollama":
                return OllamaEmbeddingModel.builder()
                        .baseUrl(env.getProperty(prefix + ".base-url", "http://localhost:11434"))
                        .modelName(env.getProperty(prefix + ".model-name", "nomic-embed-text"))
                        .build();
            
            /*
            case "gemini":
                return GeminiEmbeddingModel.builder()
                        .apiKey(env.getProperty(prefix + ".api-key"))
                        .modelName(env.getProperty(prefix + ".model-name", "text-embedding-004"))
                        .build();
            */

            case "vllm":
                // vLLM은 OpenAI 호환 API 사용
                return OpenAiEmbeddingModel.builder()
                        .baseUrl(env.getProperty(prefix + ".base-url"))
                        .apiKey(env.getProperty(prefix + ".api-key", "no-key"))
                        .modelName(env.getProperty(prefix + ".model-name"))
                        .build();

            default:
                throw new IllegalArgumentException("Unsupported embedding provider: " + provider);
        }
    }

    private void loadAndIndexDocs() throws Exception {
        Path path = Paths.get("docs", "schema", "merged_tables.json");
        File file = path.toFile();
        
        if (!file.exists()) {
            logger.warn("Knowledge file not found at {}. Skipping indexing.", file.getAbsolutePath());
            return;
        }

        logger.info("Indexing tables from {}...", file.getAbsolutePath());
        JsonNode root = objectMapper.readTree(file);
        
        if (root.isArray()) {
            List<TextSegment> segments = new ArrayList<>();
            for (JsonNode tableNode : root) {
                String tableName = tableNode.path("tableName").asText();
                String comment = tableNode.path("comment").asText();
                
                StringBuilder content = new StringBuilder();
                content.append("Table: ").append(tableName).append("\n");
                content.append("Description: ").append(comment).append("\n");
                content.append("Columns: ");
                
                JsonNode columns = tableNode.path("columns");
                if (columns.isArray()) {
                    for (JsonNode col : columns) {
                        content.append(col.path("name").asText()).append("(").append(col.path("type").asText()).append("), ");
                    }
                }
                segments.add(TextSegment.from(content.toString()));
            }
            
            if (!segments.isEmpty()) {
                logger.info("Embedding {} table definitions using {}...", segments.size(), embeddingModel.getClass().getSimpleName());
                for (TextSegment segment : segments) {
                    embeddingStore.add(embeddingModel.embed(segment).content(), segment);
                }
                logger.info("Successfully indexed {} tables.", segments.size());
            }
        }
    }

    public List<String> search(String query, int maxResults) {
        if (embeddingStore == null || embeddingModel == null) {
            return List.of("Error: VectorStore is not initialized.");
        }

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(maxResults)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        
        return searchResult.matches().stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
    }
}
