package com.sqlgen.mcp.service;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class McpService {
    private static final Logger logger = LoggerFactory.getLogger(McpService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private String dbType = null;
    
    @Value("${db.schema-output-dir:docs/schema}")
    private String defaultOutputDir;

    public McpService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setDefaultOutputDir(String dir) {
        this.defaultOutputDir = dir;
    }

    private synchronized String getDbType() {
        if (dbType != null) return dbType;
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            String driverName = conn.getMetaData().getDriverName().toLowerCase();
            if (driverName.contains("oracle")) dbType = "ORACLE";
            else if (driverName.contains("sql server") || driverName.contains("microsoft")) dbType = "MSSQL";
            else if (driverName.contains("postgresql")) dbType = "POSTGRES";
            else dbType = "UNKNOWN";
            logger.info("Detected Database Type: {}", dbType);
        } catch (SQLException e) {
            logger.error("Failed to detect database type", e);
            return "UNKNOWN";
        }
        return dbType;
    }

    private String loadSql(String path) {
        try (var is = getClass().getClassLoader().getResourceAsStream("sql/schema/" + path)) {
            if (is == null) {
                logger.warn("SQL file not found: {}", path);
                return "";
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            logger.error("Failed to load SQL file: {}", path, e);
            return "";
        }
    }

    public String getTableList() throws JsonProcessingException {
        String type = getDbType();
        String fileName = switch (type) {
            case "ORACLE" -> "oracle_tables_list.sql";
            case "POSTGRES" -> "postgres_tables_list.sql";
            case "MSSQL" -> "mssql_tables_list.sql";
            default -> "";
        };

        if (fileName.isEmpty()) {
            return "[]";
        }

        String sql = loadSql(fileName);
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql));
    }

    public String searchTables(String query) throws JsonProcessingException {
        if (query == null || query.isEmpty()) return "[]";
        
        String type = getDbType();
        String fileName = switch (type) {
            case "ORACLE" -> "oracle_tables_search.sql";
            case "POSTGRES" -> "postgres_tables_search.sql";
            case "MSSQL" -> "mssql_tables_search.sql";
            default -> "";
        };

        if (fileName.isEmpty()) {
            return "[]";
        }

        String sql = loadSql(fileName);
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql, "%" + query + "%"));
    }

    public String getTableSchema(String tableName) throws IOException {
        File tableFile = new File(defaultOutputDir + "/tables/" + tableName.toLowerCase() + ".json");
        if (tableFile.exists()) {
            return mapper.writeValueAsString(mapper.readTree(tableFile));
        }
        
        // Fallback to DB
        String type = getDbType();
        String fileName = switch (type) {
            case "ORACLE" -> "oracle_columns.sql";
            case "POSTGRES" -> "postgres_columns.sql";
            case "MSSQL" -> "mssql_columns.sql";
            default -> "";
        };

        if (fileName.isEmpty()) {
            return "[]";
        }

        String sql = loadSql(fileName);
        // columns.sql usually expect uppercase for oracle
        String param = type.equals("ORACLE") ? tableName.toUpperCase() : tableName;
        return mapper.writeValueAsString(jdbcTemplate.queryForList(sql, param));
    }

    public String executeReadQuery(String sql) throws JsonProcessingException {
        String type = getDbType();
        sql = sql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);

        String limitedSql = sql;
        boolean alreadyLimited = sql.toLowerCase().contains("limit") 
                || sql.toLowerCase().contains("rownum") 
                || sql.toLowerCase().contains("top ");

        if (!alreadyLimited) {
            limitedSql = switch (type) {
                case "POSTGRES" -> sql + " LIMIT 100";
                case "ORACLE" -> "SELECT * FROM (" + sql + ") WHERE ROWNUM <= 100";
                case "MSSQL" -> "SELECT TOP 100 * FROM (" + sql + ") AS sub";
                default -> sql;
            };
        }
        
        return mapper.writeValueAsString(jdbcTemplate.queryForList(limitedSql));
    }

    public String executeWriteQuery(String sql) {
        int affectedRows = jdbcTemplate.update(sql);
        return "{\"affectedRows\":" + affectedRows + "}";
    }

    public String explainQuery(String sql) throws JsonProcessingException {
        String type = getDbType();
        
        String explainSql;
        if (type.equals("POSTGRES")) {
            explainSql = "EXPLAIN (FORMAT JSON) " + sql;
        } else if (type.equals("ORACLE")) {
            explainSql = "EXPLAIN PLAN FOR " + sql;
        } else if (type.equals("MSSQL")) {
            explainSql = "SET SHOWPLAN_TEXT ON; " + sql; 
            // MSSQL might need different handling for SET commands in JdbcTemplate
        } else {
            explainSql = "EXPLAIN " + sql;
        }

        try {
            if (type.equals("ORACLE")) {
                jdbcTemplate.execute(explainSql);
                return mapper.writeValueAsString(jdbcTemplate.queryForList("SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)"));
            }
            if (type.equals("MSSQL")) {
                // Simplified for now, MSSQL explain can be complex via JDBC
                return "{\"info\":\"Explain for MSSQL not fully implemented via REST API yet.\"}";
            }
            return mapper.writeValueAsString(jdbcTemplate.queryForList(explainSql));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
