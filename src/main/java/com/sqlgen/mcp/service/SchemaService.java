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

    public SchemaService(DataSource dataSource) {
        this.dataSource = dataSource;
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

    public void extractAndSave(String outputDir) {
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
            save(tables, outputDir);
        } catch (SQLException e) {
            logger.error("Extraction failed", e);
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

    private List<Map<String, String>> fetchTables(Connection conn, String dbType) throws SQLException {
        String sql = switch (dbType) {
            case "ORACLE" -> "SELECT TABLE_NAME, COMMENTS as REMARK FROM USER_TAB_COMMENTS WHERE TABLE_TYPE = 'TABLE'";
            case "POSTGRES" -> "SELECT relname as TABLE_NAME, obj_description(oid) as REMARK FROM pg_class WHERE relkind = 'r' AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')";
            case "MSSQL" -> "SELECT t.name AS TABLE_NAME, CAST(p.value AS VARCHAR) AS REMARK FROM sys.tables t LEFT JOIN sys.extended_properties p ON t.object_id = p.major_id AND p.minor_id = 0 AND p.name = 'MS_Description'";
            default -> "";
        };

        List<Map<String, String>> list = new ArrayList<>();
        if (sql.isEmpty()) return list;
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
        String sql = switch (dbType) {
            case "POSTGRES" -> "SELECT ordinal_position as POS, column_name as NAME, data_type as TYPE, character_maximum_length as LEN, 'N' as PK, is_nullable as NULLABLE, '' as REMARK FROM information_schema.columns WHERE table_name = '" + tableName + "' AND table_schema = 'public' ORDER BY ordinal_position";
            case "ORACLE" -> "SELECT ATC.COLUMN_ID as POS, ATC.COLUMN_NAME as NAME, ATC.DATA_TYPE as TYPE, ATC.DATA_LENGTH as LEN, (SELECT 'Y' FROM USER_CONS_COLUMNS UCC, USER_CONSTRAINTS UC WHERE UCC.CONSTRAINT_NAME = UC.CONSTRAINT_NAME AND UC.CONSTRAINT_TYPE = 'P' AND UCC.TABLE_NAME = ATC.TABLE_NAME AND UCC.COLUMN_NAME = ATC.COLUMN_NAME) as PK, ATC.NULLABLE, ACC.COMMENTS as REMARK FROM USER_TAB_COLUMNS ATC, USER_COL_COMMENTS ACC WHERE ATC.TABLE_NAME = ACC.TABLE_NAME AND ATC.COLUMN_NAME = ACC.COLUMN_NAME AND ATC.TABLE_NAME = '" + tableName + "' ORDER BY ATC.COLUMN_ID";
            case "MSSQL" -> "SELECT c.column_id as POS, c.name as NAME, TYPE_NAME(c.user_type_id) as TYPE, c.max_length as LEN, ISNULL((SELECT 'Y' FROM sys.index_columns ic JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id WHERE i.is_primary_key = 1 AND ic.object_id = c.object_id AND ic.column_id = c.column_id), 'N') as PK, (CASE WHEN c.is_nullable = 1 THEN 'Y' ELSE 'N' END) as NULLABLE, CAST(p.value AS VARCHAR) as REMARK FROM sys.columns c LEFT JOIN sys.extended_properties p ON c.object_id = p.major_id AND c.column_id = p.minor_id AND p.name = 'MS_Description' WHERE c.object_id = OBJECT_ID('" + tableName + "') ORDER BY c.column_id";
            default -> "";
        };

        List<Map<String, String>> list = new ArrayList<>();
        if (sql.isEmpty()) return list;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
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
        return list;
    }
}
