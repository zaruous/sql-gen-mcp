package com.sqlgen.mcp.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;

@Service
public class VectorStoreService {
    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);
    
    public static int DEFAULT_SEARCH_CNT = 15;
    
    private final ObjectMapper objectMapper;
    private final Environment env;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;

    @Value("${db.schema-output-dir:docs/schema}")
    private String schemaPath;

    public VectorStoreService(ObjectMapper objectMapper, Environment env) {
        this.objectMapper = objectMapper;
        this.env = env;
    }

    @PostConstruct
    public void init() {
        String provider = env.getProperty("ai.vector-store.provider", "local");
        logger.info("Initializing VectorStore with provider: {}", provider);

        try {
            this.embeddingModel = createEmbeddingModel(provider);
            this.embeddingStore = new InMemoryEmbeddingStore<>();
            loadAndIndexDocs();
        } catch (Exception e) {
            logger.error("Failed to initialize VectorStore: {}", e.getMessage(), e);
        }
    }

    public void reload() {
        logger.info("Reloading and re-indexing knowledge base from {}...", schemaPath);
        try {
            this.embeddingStore = new InMemoryEmbeddingStore<>(); // Clear existing
            loadAndIndexDocs();
        } catch (Exception e) {
            logger.error("Failed to reload VectorStore: {}", e.getMessage(), e);
        }
    }

    private EmbeddingModel createEmbeddingModel(String provider) {
        String prefix = "ai.vector-store.providers." + provider;
        
        switch (provider.toLowerCase()) {
            case "local":
                logger.info("Using In-Process Embedding Model (AllMiniLmL6V2)...");
                return new AllMiniLmL6V2EmbeddingModel();

            case "ollama":
                return OllamaEmbeddingModel.builder()
                        .baseUrl(env.getProperty(prefix + ".base-url", "http://localhost:11434"))
                        .modelName(env.getProperty(prefix + ".model-name", "nomic-embed-text"))
                        .build();

            case "vllm":
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
    	File dir = new File(schemaPath, "tables");
    	if (!dir.exists()) {
    		dir = new File("../" + schemaPath + "/tables");
        }
        
        if (!dir.exists()) {
            logger.warn("Knowledge directory not found at {}. Skipping indexing.", dir.getAbsolutePath());
            return;
        }
        
		File[] listFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (listFiles == null) return;

		for(File file : listFiles) {
			logger.info("Indexing tables from {}...", file.getAbsolutePath());
	        JsonNode root = objectMapper.readTree(file);
	        
            List<TextSegment> segments = new ArrayList<>();

            String tableName = root.path("tableName").asText();
            String comment = root.path("comment").asText();
            
            StringBuilder content = new StringBuilder();
            content.append("Table: ").append(tableName).append("\n");
            content.append("Description: ").append(comment).append("\n");
            content.append("Columns: ");
            
            JsonNode columns = root.path("columns");
            if (columns.isArray()) {
                for (JsonNode col : columns) {
                    content.append(col.path("name").asText()).append("(").append(col.path("type").asText()).append(", ");
                    content.append("pk").append("(").append("Y".equals(col.path("pk").asText())).append("), ");
                }
            }
            
            segments.add(TextSegment.from(content.toString()));
        
            
            if (!segments.isEmpty()) {
                logger.info("Embedding {} table definitions using {}...", segments.size(), embeddingModel.getClass().getSimpleName());
                for (TextSegment segment : segments) {
                    embeddingStore.add(embeddingModel.embed(segment).content(), segment);
                }
                logger.info("Successfully indexed {} tables.", segments.size());
            }
        
		}
        
    }
    public List<String> search(String query) {
    	return search(query, DEFAULT_SEARCH_CNT);
    }
    
    public List<String> search(String query, int maxResults) {
        if (embeddingStore == null || embeddingModel == null) {
            logger.warn("RAG Search failed: VectorStore is not initialized.");
            return List.of("Error: VectorStore is not initialized.");
        }

        logger.info("\n" +
                "==================================================\n" +
                ">>> [RAG] Vector DB Search Request\n" +
                ">>> Query: {}\n" +
                "==================================================", query);

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(maxResults)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        
        List<String> results = searchResult.matches().stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());

        logger.info(">>> [RAG] Found {} relevant matches in Knowledge Base.", results.size());
		for (int i=0; i<results.size(); i++ ) {
			logger.debug(">>> [RAG] {}. Match snippet: \n{}", i, results.get(i) );
		}
        
//        if (!results.isEmpty()) {
//            logger.info(">>> [RAG] Top match summary: \n{}", 
//                results.get(0).split("\n")[0] + " (and more...)");
//        }
        
        return results;
    }
}
