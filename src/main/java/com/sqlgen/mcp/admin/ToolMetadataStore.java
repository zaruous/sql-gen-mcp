package com.sqlgen.mcp.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 툴별 추가 설명(extraDescription), 부스트 점수(boost), 부스트 발동 키워드(keywords)를 관리합니다.
 * tool-metadata.json 파일에 저장되며 런타임에 수정 가능합니다.
 *
 * keywords 동작:
 *   - 비어 있으면 쿼리 무관하게 항상 boost 적용
 *   - 지정하면 검색 쿼리에 해당 키워드 중 하나라도 포함될 때만 boost 적용
 */
@Component
public class ToolMetadataStore {

    private static final Logger log = LoggerFactory.getLogger(ToolMetadataStore.class);

    public record ToolMeta(String extraDescription, double boost, List<String> keywords) {
        public ToolMeta {
            if (boost <= 0) boost = 1.0;
            if (keywords == null) keywords = List.of();
        }
        public static ToolMeta empty() { return new ToolMeta("", 1.0, List.of()); }

        public boolean matchesQuery(String queryKeyword) {
            if (keywords.isEmpty()) return true;
            String lower = queryKeyword.toLowerCase();
            return keywords.stream().anyMatch(k ->
                    lower.contains(k.toLowerCase()) || k.toLowerCase().contains(lower));
        }
    }

    private final Map<String, ToolMeta> store = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path metaFilePath;

    public ToolMetadataStore(
            @Value("${tool.metadata.path:tool-metadata.json}") String metaFilePath) {
        this.metaFilePath = Paths.get(metaFilePath);
        load();
    }

    public ToolMeta get(String toolName) {
        return store.getOrDefault(toolName, ToolMeta.empty());
    }

    public void set(String toolName, String extraDescription, double boost) {
        set(toolName, extraDescription, boost, List.of());
    }

    public void set(String toolName, String extraDescription, double boost, List<String> keywords) {
        store.put(toolName, new ToolMeta(extraDescription, boost, keywords));
        save();
    }

    public void remove(String toolName) {
        store.remove(toolName);
        save();
    }

    public void replaceAll(Map<String, ToolMeta> newData) {
        store.clear();
        store.putAll(newData);
        save();
    }

    public Map<String, ToolMeta> getAll() {
        return Map.copyOf(store);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(metaFilePath)) return;
        try {
            Map<String, Map<String, Object>> raw =
                    objectMapper.readValue(metaFilePath.toFile(), Map.class);
            raw.forEach((toolName, meta) -> {
                String desc = (String) meta.getOrDefault("extraDescription", "");
                double boost = ((Number) meta.getOrDefault("boost", 1.0)).doubleValue();
                List<String> keywords = (List<String>) meta.getOrDefault("keywords", List.of());
                store.put(toolName, new ToolMeta(desc, boost, keywords));
            });
            log.info("[ToolMetadata] Loaded {} entries from {}", store.size(), metaFilePath);
        } catch (Exception e) {
            log.error("[ToolMetadata] Load failed: {}", e.getMessage(), e);
        }
    }

    private void save() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(metaFilePath.toFile(), store);
        } catch (Exception e) {
            log.error("[ToolMetadata] Save failed: {}", e.getMessage(), e);
        }
    }
}
