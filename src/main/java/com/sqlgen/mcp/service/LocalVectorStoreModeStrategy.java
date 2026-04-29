package com.sqlgen.mcp.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

@Component
public class LocalVectorStoreModeStrategy implements VectorStoreModeStrategy {
    // local 모드는 로컬 ONNX 임베딩과 JVM 인메모리 저장소만 사용한다.

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public String storeType() {
        return "inmemory";
    }

    @Override
    public EmbeddingModel createEmbeddingModel(Environment env) {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Override
    public EmbeddingStore<TextSegment> createTableStore(Environment env) {
        return new InMemoryEmbeddingStore<>();
    }

    @Override
    public EmbeddingStore<TextSegment> createExampleStore(Environment env) {
        return new InMemoryEmbeddingStore<>();
    }

    @Override
    public EmbeddingStore<TextSegment> resetTableStore(EmbeddingStore<TextSegment> currentStore, Environment env) {
        return new InMemoryEmbeddingStore<>();
    }

    @Override
    public EmbeddingStore<TextSegment> resetExampleStore(EmbeddingStore<TextSegment> currentStore, Environment env) {
        return new InMemoryEmbeddingStore<>();
    }
}
