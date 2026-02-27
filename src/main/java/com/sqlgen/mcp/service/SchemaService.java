package com.sqlgen.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SchemaService {
    private static final Logger logger = LoggerFactory.getLogger(SchemaService.class);
    private final DataSource dataSource;
    private String defaultOutputDir = "docs/schema";

    public SchemaService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDefaultOutputDir(String defaultOutputDir) {
        this.defaultOutputDir = defaultOutputDir;
    }

    public static class TableInfo {
        public String tableName;
        public String comment;
        public String module;
        public String fileName;
        public List<ColumnInfo> columns = new ArrayList<>();
    }

    public static class ColumnInfo {
        public String pos;
        public String name;
        public String type;
        public String len;
        public String pk;
        public String nullable;
        public String remark;
    }

    public boolean extractAndSave(String outputDir) {
        String finalDir = (outputDir == null || outputDir.isEmpty()) ? defaultOutputDir : outputDir;
        try (Connection conn = dataSource.getConnection()) {
            String dbType = getDbType(conn);
            List<TableInfo> tables = new ArrayList<>();
            List<Map<String, String>> tableMaps = fetchTables(conn, dbType);

            for (Map<String, String> tableMap : tableMaps) {
                String tableName = tableMap.get("TABLE_NAME");
                TableInfo tableInfo = new TableInfo();
                tableInfo.tableName = tableName;
                tableInfo.comment = tableMap.get("REMARK");
                tableInfo.module = getModulePrefix(tableName);
                tableInfo.fileName = tableInfo.module + "_SCHEMA.md";

                List<Map<String, String>> columnMaps = fetchColumns(conn, dbType, tableName);
                for (Map<String, String> colMap : columnMaps) {
                    ColumnInfo col = new ColumnInfo();
                    col.pos = colMap.get("POS");
                    col.name = colMap.get("NAME");
                    col.type = colMap.get("TYPE");
                    col.len = colMap.get("LEN");
                    col.pk = colMap.get("PK");
                    col.nullable = colMap.get("NULLABLE");
                    col.remark = colMap.get("REMARK");
                    tableInfo.columns.add(col);
                }
                tables.add(tableInfo);
            }
            save(tables, finalDir);
            return true;
        } catch (SQLException e) {
            logger.error("Extraction failed: {}", e.getMessage());
            return false;
        }
    }

    private String getDbType(Connection conn) throws SQLException {
        String driverName = conn.getMetaData().getDriverName().toLowerCase();
        if (driverName.contains("oracle")) return "ORACLE";
        if (driverName.contains("sql server") || driverName.contains("microsoft")) return "MSSQL";
        if (driverName.contains("postgresql")) return "POSTGRES";
        return "UNKNOWN";
    }

    private void save(List<TableInfo> tables, String outputDir) {
        File dir = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();
        File tablesDir = new File(dir, "tables");
        if (!tablesDir.exists()) tablesDir.mkdirs();

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        for (TableInfo table : tables) {
            try {
                mapper.writeValue(new File(tablesDir, table.tableName.toLowerCase() + ".json"), table);
            } catch (IOException e) {
                logger.error("JSON save failed: {}", table.tableName, e);
            }
        }

        List<Map<String, Object>> index = tables.stream().map(t -> {
            Map<String, Object> map = new HashMap<>();
            map.put("tableName", t.tableName);
            map.put("comment", t.comment);
            map.put("module", t.module);
            return map;
        }).toList();

        try {
            mapper.writeValue(new File(dir, "schema_index.json"), index);
        } catch (IOException e) {
            logger.error("Index save failed", e);
        }
    }

    private String getModulePrefix(String tableName) {
        return tableName.contains("_") ? tableName.split("_")[0].toUpperCase() : "COMMON";
    }

    private String loadSql(String path) {
        try (var is = getClass().getClassLoader().getResourceAsStream("sql/schema/" + path)) {
            if (is == null) return "";
            return new String(is.readAllBytes());
        } catch (IOException e) {
            logger.error("Failed to load SQL file: {}", path, e);
            return "";
        }
    }

    private List<Map<String, String>> fetchTables(Connection conn, String dbType) throws SQLException {
        String fileName = switch (dbType) {
            case "ORACLE" -> "oracle_tables.sql";
            case "POSTGRES" -> "postgres_tables.sql";
            case "MSSQL" -> "mssql_tables.sql";
            default -> "";
        };

        if (fileName.isEmpty()) return new ArrayList<>();
        String sql = loadSql(fileName);

        List<Map<String, String>> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> map = new HashMap<>();
                map.put("TABLE_NAME", rs.getString("TABLE_NAME"));
                map.put("REMARK", rs.getString("REMARK") != null ? rs.getString("REMARK") : "");
                list.add(map);
            }
        }
        return list;
    }

    private List<Map<String, String>> fetchColumns(Connection conn, String dbType, String tableName) throws SQLException {
        String fileName = switch (dbType) {
            case "POSTGRES" -> "postgres_columns.sql";
            case "ORACLE" -> "oracle_columns.sql";
            case "MSSQL" -> "mssql_columns.sql";
            default -> "";
        };

        if (fileName.isEmpty()) return new ArrayList<>();
        String sql = loadSql(fileName);

        List<Map<String, String>> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> map = new HashMap<>();
                    map.put("POS", rs.getString("POS"));
                    map.put("NAME", rs.getString("NAME"));
                    map.put("TYPE", rs.getString("TYPE"));
                    map.put("LEN", rs.getString("LEN") != null ? rs.getString("LEN") : "0");
                    map.put("PK", rs.getString("PK"));
                    map.put("NULLABLE", rs.getString("NULLABLE"));
                    map.put("REMARK", rs.getString("REMARK") != null ? rs.getString("REMARK") : "");
                    list.add(map);
                }
            }
        }
        return list;
    }
}
