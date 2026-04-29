package com.sqlgen.mcp.service;

import org.springframework.core.env.Environment;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

public interface VectorStoreModeStrategy {
    // provider 값과 실제 임베딩/저장소 구현을 연결하는 전략 인터페이스

    String provider();

    String storeType();

    EmbeddingModel createEmbeddingModel(Environment env);

    EmbeddingStore<TextSegment> createTableStore(Environment env);

    EmbeddingStore<TextSegment> createExampleStore(Environment env);

    EmbeddingStore<TextSegment> resetTableStore(EmbeddingStore<TextSegment> currentStore, Environment env);

    EmbeddingStore<TextSegment> resetExampleStore(EmbeddingStore<TextSegment> currentStore, Environment env);
}
