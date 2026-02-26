package com.sqlgen.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class McpService {
    private static final Logger logger = LoggerFactory.getLogger(McpService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode schemaIndexCache = null;

    public McpService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        loadSchemaIndex();
    }

    private void loadSchemaIndex() {
        try (var is = getClass().getClassLoader().getResourceAsStream("docs/schema/schema_index.json")) {
            if (is != null) {
                schemaIndexCache = mapper.readTree(is);
                logger.info("Schema index loaded from classpath.");
            }
        } catch (Exception e) {
            logger.error("Schema load error", e);
        }
    }

    public String getTableList() throws JsonProcessingException {
        return mapper.writeValueAsString(schemaIndexCache != null ? schemaIndexCache : "No schema index found.");
    }

    public String searchTables(String query) throws JsonProcessingException {
        if (schemaIndexCache == null || !schemaIndexCache.isArray()) return "[]";
        List<JsonNode> results = new ArrayList<>();
        String q = query.toLowerCase();
        for (JsonNode item : schemaIndexCache) {
            if (item.path("tableName").asText().toLowerCase().contains(q) ||
                item.path("comment").asText().toLowerCase().contains(q)) {
                results.add(item);
            }
        }
        return mapper.writeValueAsString(results);
    }

    public String getTableSchema(String tableName) {
        try (var is = getClass().getClassLoader().getResourceAsStream("docs/schema/tables/" + tableName.toLowerCase() + ".json")) {
            if (is == null) return "Table '" + tableName + "' not found.";
            return mapper.writeValueAsString(mapper.readTree(is));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public String executeReadQuery(String sql) throws JsonProcessingException {
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql));
    }

    public String executeWriteQuery(String sql) {
        int affectedRows = jdbcTemplate.update(sql);
        return "{\"affectedRows\":" + affectedRows + "}";
    }
}
