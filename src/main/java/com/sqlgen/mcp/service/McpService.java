package com.sqlgen.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class McpService {
    private static final Logger logger = LoggerFactory.getLogger(McpService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private String defaultOutputDir = "docs/schema";

    public McpService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setDefaultOutputDir(String dir) {
        this.defaultOutputDir = dir;
    }

    public String getTableList() throws JsonProcessingException {
        // 대규모 데이터 부하 방지: 상위 100개 및 필수 컬럼만 조회
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' LIMIT 100";
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql));
    }

    public String searchTables(String query) throws JsonProcessingException {
        if (query == null || query.isEmpty()) return "[]";
        // 검색 결과도 50개로 제한
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE ? LIMIT 50";
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql, "%" + query.toLowerCase() + "%"));
    }

    public String getTableSchema(String tableName) throws IOException {
        File tableFile = new File(defaultOutputDir + "/tables/" + tableName.toLowerCase() + ".json");
        if (tableFile.exists()) {
            return mapper.writeValueAsString(mapper.readTree(tableFile));
        }
        
        // Fallback to DB
        String sql = "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = ?";
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql, tableName));
    }

    public String executeReadQuery(String sql) throws JsonProcessingException {
        // Safety: Limit rows
        if (!sql.toLowerCase().contains("limit")) {
            sql = sql.trim();
            if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);
            sql += " LIMIT 100";
        }
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql));
    }

    public String executeWriteQuery(String sql) {
        int affectedRows = jdbcTemplate.update(sql);
        return "{\"affectedRows\":" + affectedRows + "}";
    }

    public String explainQuery(String sql) throws JsonProcessingException {
        String driver = "";
        try (java.sql.Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            driver = conn.getMetaData().getURL();
        } catch (Exception ignored) {}
        
        String explainSql;
        if (driver.contains("postgresql")) {
            explainSql = "EXPLAIN (FORMAT JSON) " + sql;
        } else if (driver.contains("oracle")) {
            explainSql = "EXPLAIN PLAN FOR " + sql;
        } else {
            explainSql = "EXPLAIN " + sql;
        }

        try {
            if (driver.contains("oracle")) {
                jdbcTemplate.execute(explainSql);
                return mapper.writeValueAsString(jdbcTemplate.queryForList("SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)"));
            }
            return mapper.writeValueAsString(jdbcTemplate.queryForList(explainSql));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
