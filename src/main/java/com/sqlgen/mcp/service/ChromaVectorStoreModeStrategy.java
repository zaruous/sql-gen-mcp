package com.sqlgen.mcp.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;

@Component
public class ChromaVectorStoreModeStrategy implements VectorStoreModeStrategy {
    // chroma 모드는 임베딩은 로컬에서 수행하고 벡터 저장소만 ChromaDB로 위임한다.

    private static final String PREFIX = "ai.vector-store.chroma";

    @Override
    public String provider() {
        return "chroma";
    }

    @Override
    public String storeType() {
        return "chroma";
    }

    @Override
    public EmbeddingModel createEmbeddingModel(Environment env) {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Override
    public EmbeddingStore<TextSegment> createTableStore(Environment env) {
        return buildStore(env, ".table-collection-name", "sql_gen_tables");
    }

    @Override
    public EmbeddingStore<TextSegment> createExampleStore(Environment env) {
        return buildStore(env, ".example-collection-name", "sql_gen_examples");
    }

    @Override
    public EmbeddingStore<TextSegment> resetTableStore(EmbeddingStore<TextSegment> currentStore, Environment env) {
        return clearOrRecreate(currentStore, () -> createTableStore(env));
    }

    @Override
    public EmbeddingStore<TextSegment> resetExampleStore(EmbeddingStore<TextSegment> currentStore, Environment env) {
        return clearOrRecreate(currentStore, () -> createExampleStore(env));
    }

    private EmbeddingStore<TextSegment> buildStore(Environment env, String collectionSuffix, String defaultCollection) {
        // 테이블 검색과 SQL 예시 검색은 컬렉션만 다르고 연결 파라미터는 동일하다.
        String url = env.getProperty(PREFIX + ".url", "http://localhost:18000");
        String collection = env.getProperty(PREFIX + collectionSuffix,
                env.getProperty(PREFIX + ".collection-name", defaultCollection));
        String tenant = env.getProperty(PREFIX + ".tenant", "default_tenant");
        String database = env.getProperty(PREFIX + ".database", "default_database");
        return ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(url)
                .tenantName(tenant)
                .databaseName(database)
                .collectionName(collection)
                .build();
    }

    private EmbeddingStore<TextSegment> clearOrRecreate(EmbeddingStore<TextSegment> currentStore,
                                                        java.util.function.Supplier<EmbeddingStore<TextSegment>> recreate) {
        try {
            // 가능하면 기존 컬렉션을 비우고 재사용해 연결 설정과 컬렉션 정보를 유지한다.
            currentStore.removeAll();
            return currentStore;
        } catch (Exception e) {
            // removeAll 실패 시 store 객체를 새로 만들어 재인덱싱을 계속 진행한다.
            return recreate.get();
        }
    }
}
