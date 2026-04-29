package com.sqlgen.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlgen.mcp.model.SqlExample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SqlExampleStore {
    private static final Logger log = LoggerFactory.getLogger(SqlExampleStore.class);

    private final Map<String, SqlExample> store = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path storeFilePath;

    public SqlExampleStore(ObjectMapper objectMapper,
                           @Value("${sql.examples.path:sql-examples.json}") String path) {
        this.objectMapper = objectMapper;
        this.storeFilePath = Paths.get(path);
        load();
    }

    public List<SqlExample> getAll() {
        return new ArrayList<>(store.values());
    }

    public SqlExample get(String id) {
        return store.get(id);
    }

    public void save(SqlExample example) {
        store.put(example.getId(), example);
        persist();
    }

    public void delete(String id) {
        store.remove(id);
        persist();
    }

    public void replaceAll(List<SqlExample> examples) {
        store.clear();
        examples.forEach(e -> store.put(e.getId(), e));
        persist();
    }

    private void load() {
        if (!Files.exists(storeFilePath)) return;
        try {
            List<SqlExample> list = objectMapper.readValue(
                storeFilePath.toFile(), new TypeReference<List<SqlExample>>() {});
            list.forEach(e -> store.put(e.getId(), e));
            log.info("[SqlExampleStore] Loaded {} examples from {}", store.size(), storeFilePath);
        } catch (Exception e) {
            log.error("[SqlExampleStore] Load failed: {}", e.getMessage(), e);
        }
    }

    private void persist() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(storeFilePath.toFile(), new ArrayList<>(store.values()));
        } catch (Exception e) {
            log.error("[SqlExampleStore] Save failed: {}", e.getMessage(), e);
        }
    }
}
