package com.sqlgen.mcp.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
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
            // 인덱싱은 스키마 추출 완료 후 reload()에서 수행
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
            content.append("Columns:\n");

            JsonNode columns = root.path("columns");
            if (columns.isArray()) {
                for (JsonNode col : columns) {
                    String colName   = col.path("name").asText();
                    String colType   = col.path("type").asText();
                    String colPk     = "Y".equals(col.path("pk").asText()) ? " PK" : "";
                    String colRemark = col.path("remark").asText("").trim();

                    content.append("  - ").append(colName)
                           .append(" (").append(colType).append(colPk).append(")");
                    if (!colRemark.isEmpty()) {
                        content.append(" : ").append(colRemark);
                    }
                    content.append("\n");
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
        String[] keywords = query.split(",");
        if (keywords.length == 1) {
            return toTextList(searchMatches(query.trim(), DEFAULT_SEARCH_CNT));
        }
        // 다건 검색: 키워드별 검색 후 텍스트 기준 중복 제거, score 내림차순 재정렬
        Map<String, EmbeddingMatch<TextSegment>> merged = new LinkedHashMap<>();
        for (String keyword : keywords) {
            String kw = keyword.trim();
            if (kw.isEmpty()) continue;
            for (EmbeddingMatch<TextSegment> match : searchMatches(kw, DEFAULT_SEARCH_CNT)) {
                String text = match.embedded().text();
                // 같은 텍스트가 중복될 경우 더 높은 score 유지
                merged.merge(text, match, (existing, incoming) ->
                        incoming.score() > existing.score() ? incoming : existing);
            }
        }
        return toTextList(
                merged.values().stream()
                        .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed())
                        .collect(Collectors.toList())
        );
    }

    public List<String> search(String query, int maxResults) {
        return toTextList(searchMatches(query, Math.min(maxResults, 30)));
    }

    private List<EmbeddingMatch<TextSegment>> searchMatches(String query, int maxResults) {
        if (embeddingStore == null || embeddingModel == null) {
            logger.warn("RAG Search failed: VectorStore is not initialized.");
            return List.of();
        }

        logger.info("\n==================================================\n" +
                    ">>> [RAG] Vector DB Search Request\n" +
                    ">>> Query: {}\n" +
                    "==================================================", query);

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(maxResults)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();

        logger.info(">>> [RAG] Found {} relevant matches in Knowledge Base.", matches.size());
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> m = matches.get(i);
            logger.info(">>> [RAG] {}. score={} ({}) | {}",
                    i + 1,
                    String.format("%.4f", m.score()),
                    String.format("%.1f%%", m.score() * 100),
                    m.embedded().text().split("\n")[0]);
        }
        return matches;
    }

    private List<String> toTextList(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .map(m -> m.embedded().text())
                .collect(Collectors.toList());
    }
}
