package com.sqlgen.mcp.service;

import com.sqlgen.mcp.model.SqlExample;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SqlExampleService {
    private static final Logger logger = LoggerFactory.getLogger(SqlExampleService.class);

    private static final String ID_PREFIX = "[SQLEXAMPLE_ID:";

    private final SqlExampleStore exampleStore;
    private final VectorStoreService vectorStoreService;
    private final Environment env;

    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;

    private final Map<String, SqlExample> indexedExamples = new ConcurrentHashMap<>();

    public SqlExampleService(SqlExampleStore exampleStore, VectorStoreService vectorStoreService,
                             Environment env) {
        this.exampleStore = exampleStore;
        this.vectorStoreService = vectorStoreService;
        this.env = env;
    }

    @PostConstruct
    public void init() {
        this.embeddingModel = vectorStoreService.getEmbeddingModel();
        this.embeddingStore = createEmbeddingStore();
        reindexAll();
    }

    private EmbeddingStore<TextSegment> createEmbeddingStore() {
        String backend = env.getProperty("ai.vector-store.backend", "inmemory");
        if ("chroma".equalsIgnoreCase(backend)) {
            String prefix = "ai.vector-store.backends.chroma";
            String host = env.getProperty(prefix + ".host", "http://localhost:8000");
            String collection = env.getProperty(prefix + ".examples-collection", "sql_gen_examples");
            logger.info("[SqlExamples] Using ChromaDB: {} / collection={}", host, collection);
            return dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore.builder()
                .baseUrl(host)
                .collectionName(collection)
                .build();
        }
        logger.info("[SqlExamples] Using InMemoryEmbeddingStore");
        return new InMemoryEmbeddingStore<>();
    }

    private void reindexAll() {
        if (embeddingModel == null) {
            logger.warn("[SqlExamples] EmbeddingModel not ready, skipping reindex");
            return;
        }
        indexedExamples.clear();

        // Clear existing embeddings: InMemory → new instance; Chroma → try removeAll
        String backend = env.getProperty("ai.vector-store.backend", "inmemory");
        if ("chroma".equalsIgnoreCase(backend)) {
            try {
                embeddingStore.removeAll();
            } catch (Exception e) {
                logger.warn("[SqlExamples] removeAll() unsupported, recreating store: {}", e.getMessage());
                embeddingStore = createEmbeddingStore();
            }
        } else {
            embeddingStore = new InMemoryEmbeddingStore<>();
        }

        List<SqlExample> all = exampleStore.getAll();
        for (SqlExample ex : all) {
            embedAndIndex(ex);
        }
        logger.info("[SqlExamples] Indexed {} examples", all.size());
    }

    private void embedAndIndex(SqlExample ex) {
        String text = buildEmbedText(ex);
        TextSegment segment = TextSegment.from(text);
        embeddingStore.add(embeddingModel.embed(segment).content(), segment);
        indexedExamples.put(ex.getId(), ex);
    }

    private String buildEmbedText(SqlExample ex) {
        StringBuilder sb = new StringBuilder();
        // First line encodes the ID for reverse lookup after vector search
        sb.append(ID_PREFIX).append(ex.getId()).append("]\n");
        sb.append("Title: ").append(ex.getTitle()).append("\n");
        if (ex.getDescription() != null && !ex.getDescription().isBlank()) {
            sb.append("Description: ").append(ex.getDescription()).append("\n");
        }
        if (ex.getCategory() != null && !ex.getCategory().isBlank()) {
            sb.append("Category: ").append(ex.getCategory()).append("\n");
        }
        if (ex.getTags() != null && !ex.getTags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", ex.getTags())).append("\n");
        }
        sb.append("SQL:\n").append(ex.getSql());
        return sb.toString();
    }

    private String extractId(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        if (firstLine.startsWith(ID_PREFIX) && firstLine.endsWith("]")) {
            return firstLine.substring(ID_PREFIX.length(), firstLine.length() - 1);
        }
        return null;
    }

    public SqlExample add(String title, String description, String sql,
                          String category, List<String> tags) {
        SqlExample ex = SqlExample.create(title, description, sql, category, tags);
        exampleStore.save(ex);
        if (embeddingModel != null) {
            embedAndIndex(ex);
        }
        return ex;
    }

    public SqlExample update(String id, String title, String description, String sql,
                             String category, List<String> tags) {
        SqlExample ex = exampleStore.get(id);
        if (ex == null) return null;
        ex.setTitle(title != null ? title : ex.getTitle());
        ex.setDescription(description != null ? description : ex.getDescription());
        ex.setSql(sql != null ? sql : ex.getSql());
        ex.setCategory(category != null ? category : ex.getCategory());
        ex.setTags(tags != null ? tags : ex.getTags());
        ex.setUpdatedAt(Instant.now().toString());
        exampleStore.save(ex);
        reindexAll();
        return ex;
    }

    public boolean delete(String id) {
        if (exampleStore.get(id) == null) return false;
        exampleStore.delete(id);
        reindexAll();
        return true;
    }

    public List<SqlExample> list() {
        return exampleStore.getAll();
    }

    public List<Map<String, Object>> search(String query, int maxResults) {
        if (embeddingStore == null || embeddingModel == null || indexedExamples.isEmpty()) {
            return List.of();
        }

        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
            .queryEmbedding(embeddingModel.embed(query).content())
            .maxResults(Math.min(maxResults, 20))
            .minScore(0.25)
            .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(req).matches();
        logger.info("[SqlExamples] search='{}' → {} matches", query, matches.size());

        List<Map<String, Object>> results = new ArrayList<>();
        int rank = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            String exId = extractId(match.embedded().text());
            if (exId == null) continue;
            SqlExample ex = indexedExamples.get(exId);
            if (ex == null) continue;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rank",        rank++);
            r.put("score",       match.score());
            r.put("id",          ex.getId());
            r.put("title",       ex.getTitle());
            r.put("description", ex.getDescription());
            r.put("category",    ex.getCategory());
            r.put("tags",        ex.getTags());
            r.put("sql",         ex.getSql());
            results.add(r);
        }
        return results;
    }

    /** MCP 도구용: 검색 결과를 텍스트 블록으로 반환 */
    public String searchAsText(String query, int maxResults) {
        List<Map<String, Object>> results = search(query, maxResults);
        if (results.isEmpty()) return "검색 결과가 없습니다.";

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : results) {
            sb.append("=== [").append(r.get("rank")).append("] ")
              .append(r.get("title")).append(" (score: ")
              .append(String.format("%.2f", r.get("score"))).append(") ===\n");
            if (r.get("description") != null && !r.get("description").toString().isBlank()) {
                sb.append("설명: ").append(r.get("description")).append("\n");
            }
            if (r.get("category") != null && !r.get("category").toString().isBlank()) {
                sb.append("분류: ").append(r.get("category")).append("\n");
            }
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) r.get("tags");
            if (tags != null && !tags.isEmpty()) {
                sb.append("태그: ").append(String.join(", ", tags)).append("\n");
            }
            sb.append("SQL:\n").append(r.get("sql")).append("\n\n");
        }
        return sb.toString().trim();
    }

    public List<SqlExample> importExamples(List<SqlExample> examples) {
        exampleStore.replaceAll(examples);
        reindexAll();
        return exampleStore.getAll();
    }

    public List<SqlExample> exportExamples() {
        return exampleStore.getAll();
    }

    public int getCount() {
        return indexedExamples.size();
    }
}
