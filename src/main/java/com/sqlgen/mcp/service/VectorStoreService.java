package com.sqlgen.mcp.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlgen.mcp.admin.ToolMetadataStore;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;

@Service
public class VectorStoreService {
    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);

    public static int DEFAULT_SEARCH_CNT = 15;
    private static final double PREFIX_M_BASE_BOOST = 0.10;
    private static final double PREFIX_H_BASE_BOOST = 0.03;
    private static final double PREFIX_D_BASE_BOOST = 0.02;
    private static final double MASTER_INTENT_EXTRA_BOOST = 0.08;
    private static final List<String> MASTER_INTENT_KEYWORDS =
            List.of("마스터", "master", "기준", "기준정보", "코드", "공통", "설정", "기본정보");

    public record TableSummary(String tableName, String comment, int columnCount) {}

    private final ObjectMapper objectMapper;
    private final Environment env;
    private final ToolMetadataStore metadataStore;
    private final KoreanQueryTranslator koreanTranslator;
    private final VectorStoreModeResolver modeResolver;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private VectorStoreModeStrategy modeStrategy;
    private volatile boolean ready = false;
    private volatile int tableCount = 0;
    private final List<TableSummary> tableSummaries = new ArrayList<>();

    /** 테이블명 → 임베딩에 사용한 전체 텍스트 (검색 결과 반환용) */
    private final Map<String, String> tableContentText = new ConcurrentHashMap<>();
    /** 테이블명 → 이름 자체 매칭용 텍스트 */
    private final Map<String, String> tableNameKeywordText = new ConcurrentHashMap<>();
    /** 테이블명 → 코멘트 매칭용 텍스트 */
    private final Map<String, String> tableCommentKeywordText = new ConcurrentHashMap<>();
    /** 테이블명 → 컬럼명 매칭용 텍스트 */
    private final Map<String, String> tableColumnKeywordText = new ConcurrentHashMap<>();
    /** 테이블명 → remark 매칭용 텍스트 */
    private final Map<String, String> tableRemarkKeywordText = new ConcurrentHashMap<>();

    @Value("${db.schema-output-dir:docs/schema}")
    private String schemaPath;

    public VectorStoreService(ObjectMapper objectMapper, Environment env, ToolMetadataStore metadataStore,
                              KoreanQueryTranslator koreanTranslator,
                              VectorStoreModeResolver modeResolver) {
        this.objectMapper = objectMapper;
        this.env = env;
        this.metadataStore = metadataStore;
        this.koreanTranslator = koreanTranslator;
        this.modeResolver = modeResolver;
    }

    @PostConstruct
    public void init() {
        try {
            this.modeStrategy = modeResolver.resolve(env);
            // provider에 따라 구현체만 바꾸고, 서비스 본문은 전략 인터페이스에만 의존한다.
            logger.info("Initializing VectorStore with provider={} using strategy={}",
                    modeStrategy.provider(), modeStrategy.getClass().getSimpleName());
            this.embeddingModel = modeStrategy.createEmbeddingModel(env);
            this.embeddingStore = modeStrategy.createTableStore(env);
            // 인덱싱은 스키마 추출 완료 후 reload()에서 수행
        } catch (Exception e) {
            logger.error("Failed to initialize VectorStore: {}", e.getMessage(), e);
        }
    }

    public void reload() {
        logger.info("Reloading and re-indexing knowledge base from {}...", schemaPath);
        ready = false;
        try {
            this.embeddingStore = modeStrategy.resetTableStore(this.embeddingStore, env);
            tableContentText.clear();
            tableNameKeywordText.clear();
            tableCommentKeywordText.clear();
            tableColumnKeywordText.clear();
            tableRemarkKeywordText.clear();
            loadAndIndexDocs();
        } catch (Exception e) {
            logger.error("Failed to reload VectorStore: {}", e.getMessage(), e);
        }
    }

    public boolean isReady() { return ready; }
    public int getTableCount() { return tableCount; }
    public List<TableSummary> getTableSummaries() { return List.copyOf(tableSummaries); }
    public EmbeddingModel getEmbeddingModel() { return embeddingModel; }
    public Environment getEnvironment() { return env; }
    public String getConfiguredProvider() { return modeStrategy.provider(); }
    public String getStoreType() { return modeStrategy.storeType(); }

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
        tableCount = 0;
        tableSummaries.clear();
        tableContentText.clear();
        tableNameKeywordText.clear();
        tableCommentKeywordText.clear();
        tableColumnKeywordText.clear();
        tableRemarkKeywordText.clear();

        for (File file : listFiles) {
            logger.info("Indexing tables from {}...", file.getAbsolutePath());
            JsonNode root = objectMapper.readTree(file);

            List<TextSegment> segments = new ArrayList<>();

            String tableName = root.path("tableName").asText();
            String comment   = root.path("comment").asText();
            
            StringBuilder content = new StringBuilder();
            content.append("Table: ").append(tableName).append("\n");
            content.append("Description: ").append(comment).append("\n");
            content.append("Columns:\n");

            // 검색 시 이름/코멘트/컬럼/remark에 서로 다른 가중치를 주기 위해 분리 저장한다.
            String nameKeyword = tableName.toLowerCase();
            String commentKeyword = comment.toLowerCase();
            StringBuilder columnKeyword = new StringBuilder();
            StringBuilder remarkKeyword = new StringBuilder();

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

                    columnKeyword.append(colName.toLowerCase()).append(" ");
                    remarkKeyword.append(colRemark.toLowerCase()).append(" ");
                }
            }

            segments.add(TextSegment.from(content.toString()));
            tableContentText.put(tableName, content.toString());
            tableNameKeywordText.put(tableName, nameKeyword);
            tableCommentKeywordText.put(tableName, commentKeyword);
            tableColumnKeywordText.put(tableName, columnKeyword.toString());
            tableRemarkKeywordText.put(tableName, remarkKeyword.toString());

            int colCount = columns.isArray() ? columns.size() : 0;
            tableSummaries.add(new TableSummary(tableName, comment, colCount));

            if (!segments.isEmpty()) {
                logger.info("Embedding {} table definitions using {}...", segments.size(), embeddingModel.getClass().getSimpleName());
                for (TextSegment segment : segments) {
                    embeddingStore.add(embeddingModel.embed(segment).content(), segment);
                }
                tableCount++;
                logger.info("Successfully indexed {} tables.", segments.size());
            }

        }
        ready = true;
        logger.info("Knowledge base indexing complete. Total tables: {}", tableCount);
    }
    public List<String> search(String query) {
        return hybridSearch(query, DEFAULT_SEARCH_CNT);
    }

    public List<String> search(String query, int maxResults) {
        return hybridSearch(query, Math.min(maxResults, 30));
    }

    /**
     * 하이브리드 검색: 텍스트 키워드 매칭 + 벡터 유사도 병합.
     *
     * 점수 체계 (텍스트 매칭은 한국어에서 특히 유리):
     *   - 테이블명 정확 일치      : 1.00
     *   - 테이블명 포함           : 0.90
     *   - 코멘트/컬럼명/remark 포함: 0.70
     *   - 벡터 유사도             : cosine * 0.85 (영어 특화 모델 보정)
     * 같은 테이블에 여러 점수가 있으면 최대값 사용.
     */
    private List<String> hybridSearch(String query, int maxResults) {
        if (embeddingStore == null || embeddingModel == null) {
            logger.warn("Search failed: VectorStore is not initialized.");
            return List.of();
        }

        // 한국어 키워드를 영어로 번역하여 쉼표로 추가 (원문은 텍스트 매칭, 번역어는 벡터 검색에 기여)
        StringBuilder expandedQuery = new StringBuilder(query);
        for (String raw : query.split(",")) {
            String translated = koreanTranslator.translate(raw.trim());
            if (!translated.equals(raw.trim()) && !translated.isBlank()) {
                expandedQuery.append(",").append(translated);
                logger.info("[Hybrid] Korean expanded: '{}' → appended '{}'", raw.trim(), translated);
            }
        }
        String[] keywords = expandedQuery.toString().split(",");
        // tableName → 최고 점수
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        // "마스터/기준/코드" 계열 질의는 M-prefix 테이블에 추가 가산점을 준다.
        boolean masterIntent = hasMasterIntent(keywords);

        for (String raw : keywords) {
            String kw    = raw.trim();
            String kwLow = kw.toLowerCase();
            if (kwLow.isEmpty()) continue;

            // ── 1. 텍스트 키워드 매칭 (한국어 친화적) ──────────────────────
            for (String tName : tableNameKeywordText.keySet()) {
                double textScore = calculateTextScore(tName, kwLow);
                if (textScore > 0) {
                    scoreMap.merge(tName, textScore, Math::max);
                }
            }

            // ── 2. 벡터 유사도 검색 (영어 의미 커버) ──────────────────────
            logger.info("[Hybrid] Vector search: '{}'", kw);
            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(kw).content())
                    .maxResults(maxResults * 2)
                    .minScore(0.3)
                    .build();
            List<EmbeddingMatch<TextSegment>> vectorMatches = embeddingStore.search(req).matches();

            for (EmbeddingMatch<TextSegment> match : vectorMatches) {
                String text      = match.embedded().text();
                String firstLine = text.lines().findFirst().orElse("").trim();
                String tName     = firstLine.startsWith("Table:") ? firstLine.substring(6).trim() : firstLine;
                double vecScore  = match.score() * 0.85; // 영어 모델 보정
                scoreMap.merge(tName, vecScore, Math::max);
            }
        }

        // ── 3. tool-metadata.json 가중치 적용 ────────────────────────────
        // keywords 가 비어 있으면 모든 쿼리에 적용, 있으면 쿼리 키워드 포함 시에만 적용
        Map<String, ToolMetadataStore.ToolMeta> allMeta = metadataStore.getAll();
        if (!allMeta.isEmpty()) {
            final String[] kwArr = keywords;
            scoreMap.replaceAll((tableName, score) -> {
                if (!allMeta.containsKey(tableName)) return score;
                ToolMetadataStore.ToolMeta meta = allMeta.get(tableName);
                boolean shouldBoost = java.util.Arrays.stream(kwArr)
                        .anyMatch(k -> meta.matchesQuery(k.trim()));
                if (shouldBoost) {
                    double boosted = score * meta.boost();
                    logger.debug("[Hybrid] boost applied: table={} {}× → score {} → {}",
                            tableName, meta.boost(), String.format("%.4f", score), String.format("%.4f", boosted));
                    return boosted;
                }
                return score;
            });
        }

        // ── 4. prefix / 의도 기반 가산점 적용 ──────────────────────────────
        scoreMap.replaceAll((tableName, score) -> score + calculatePrefixBoost(tableName, masterIntent));

        // ── 5. 점수 내림차순 정렬 후 반환 ─────────────────────────────────
        List<String> result = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(e -> tableContentText.getOrDefault(e.getKey(), "Table: " + e.getKey()))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());

        logger.info("[Hybrid] query='{}' → {} results", query, result.size());
        if (logger.isDebugEnabled()) {
            scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(maxResults)
                    .forEach(e -> logger.debug("[Hybrid]  score={} table={}",
                            String.format("%.4f", e.getValue()), e.getKey()));
        }

        return result;
    }

    private double calculateTextScore(String tableName, String keywordLower) {
        String nameLow = tableNameKeywordText.getOrDefault(tableName, "");
        String commentLow = tableCommentKeywordText.getOrDefault(tableName, "");
        String columnLow = tableColumnKeywordText.getOrDefault(tableName, "");
        String remarkLow = tableRemarkKeywordText.getOrDefault(tableName, "");

        // 테이블명 > 컬럼명 > 코멘트 > remark 순으로 신뢰도를 다르게 본다.
        if (nameLow.equals(keywordLower)) {
            return 1.20;
        }
        if (nameLow.contains(keywordLower)) {
            return 1.00;
        }
        if (columnLow.contains(keywordLower)) {
            return 0.85;
        }
        if (commentLow.contains(keywordLower)) {
            return 0.72;
        }
        if (remarkLow.contains(keywordLower)) {
            return 0.65;
        }
        return 0.0;
    }

    private boolean hasMasterIntent(String[] keywords) {
        for (String keyword : keywords) {
            String lowered = keyword.trim().toLowerCase();
            if (lowered.isEmpty()) continue;
            for (String intent : MASTER_INTENT_KEYWORDS) {
                if (lowered.contains(intent.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private double calculatePrefixBoost(String tableName, boolean masterIntent) {
        String upper = tableName.toUpperCase();
        double boost = 0.0;
        if (upper.startsWith("M")) {
            // M-prefix는 master성 테이블이라는 도메인 가정을 기본 점수로 반영한다.
            boost += PREFIX_M_BASE_BOOST;
            if (masterIntent) {
                boost += MASTER_INTENT_EXTRA_BOOST;
            }
        } else if (upper.startsWith("H")) {
            boost += PREFIX_H_BASE_BOOST;
        } else if (upper.startsWith("D")) {
            boost += PREFIX_D_BASE_BOOST;
        }
        return boost;
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
