package com.sqlgen.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlgen.mcp.admin.ToolMetadataStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 기본 테스트 TODO: 실제 파일 로딩과 인덱싱을 포함하는 통합 테스트로 확장 필요
 */
class VectorStoreServiceTest {

    private VectorStoreService vectorStoreService;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Environment env = Mockito.mock(Environment.class);
        when(env.getProperty("ai.vector-store.provider", "local")).thenReturn("local");
        ToolMetadataStore metadataStore = Mockito.mock(ToolMetadataStore.class);
        when(metadataStore.getAll()).thenReturn(java.util.Map.of());

        vectorStoreService = new VectorStoreService(objectMapper, env, metadataStore);

        // 로컬 임베딩 모델 직접 생성 (테스트용)
        embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        embeddingStore = new InMemoryEmbeddingStore<>();

        // 리플렉션을 사용하여 private 필드에 주입 (init()에서 로컬 파일 로드를 건너뛰기 위함)
        setInternalField(vectorStoreService, "embeddingModel", embeddingModel);
        setInternalField(vectorStoreService, "embeddingStore", embeddingStore);
    }

    private void setInternalField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("메모리에 데이터를 로드하고 사용자의 질문과 유사한 테이블을 찾는지 테스트")
    void testVectorSearchSimilarity() {
        // 1. 초기 데이터 (테이블 정의서) 직접 인덱싱
        // 인코딩 문제를 피하기 위해 영문 키워드 위주로 설명 구성
        indexMockTable("USER_INFO", "Store user profile, email, password and login account information.");
        indexMockTable("ORDER_HISTORY", "Manage customer order history, shipping status, and payment details.");
        indexMockTable("PRODUCT_STOCK", "Save product stock quantity, warehouse location and inventory levels.");

        // 2. 사용자 입력에 따른 검색 테스트
        
        // Case 1: "User login account" -> USER_INFO와 유사해야 함
        List<String> userResults = vectorStoreService.search("User login account", 1);
        assertThat(userResults).isNotEmpty();
        assertThat(userResults.get(0)).contains("USER_INFO");

        // Case 2: "Order and shipping status" -> ORDER_HISTORY와 유사해야 함
        List<String> orderResults = vectorStoreService.search("Order and shipping status", 1);
        assertThat(orderResults).isNotEmpty();
        assertThat(orderResults.get(0)).contains("ORDER_HISTORY");

        // Case 3: "Warehouse inventory quantity" -> PRODUCT_STOCK과 유사해야 함
        List<String> stockResults = vectorStoreService.search("Warehouse inventory quantity", 1);
        assertThat(stockResults).isNotEmpty();
        assertThat(stockResults.get(0)).contains("PRODUCT_STOCK");
    }

    private void indexMockTable(String tableName, String comment) {
        String content = String.format("Table: %s\nDescription: %s\nColumns: ID(VARCHAR), NAME(VARCHAR)", tableName, comment);
        TextSegment segment = TextSegment.from(content);
        embeddingStore.add(embeddingModel.embed(segment).content(), segment);
    }
}
