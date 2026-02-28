package com.sqlgen.mcp.handler;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import com.sqlgen.mcp.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpHandler {
    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);
    private final McpService mcpService;

    public McpHandler(McpService mcpService) {
        this.mcpService = mcpService;
    }

    public McpSyncServer createServer(McpServerTransportProvider transportProvider) {
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("SQL-Gen-MCP-Server", "1.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .logging()
                        .build())
                .build();

        registerTools(server);
        return server;
    }

    private void registerTools(McpSyncServer server) {
        // 1. Get Table List
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("get_table_list")
                .description("DB 테이블 목록 및 코멘트 조회")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build())
            .callHandler((exchange, request) -> {
                try {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(mcpService.getTableList())))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                        .isError(true)
                        .build();
                }
            })
            .build());

        // 2. Search Tables
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("search_tables")
                .description("키워드로 테이블 검색")
                .inputSchema(new McpSchema.JsonSchema("object", 
                    Map.of("query", Map.of("type", "string", "description", "검색 키워드")), 
                    List.of("query"), false, null, null))
                .build())
            .callHandler((exchange, request) -> {
                String query = (String) request.arguments().get("query");
                try {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(mcpService.searchTables(query))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                        .isError(true)
                        .build();
                }
            })
            .build());

        // 3. Get Table Schema
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("get_table_schema")
                .description("특정 테이블의 상세 스키마(컬럼 정보) 조회")
                .inputSchema(new McpSchema.JsonSchema("object", 
                    Map.of("tableName", Map.of("type", "string", "description", "테이블명")), 
                    List.of("tableName"), false, null, null))
                .build())
            .callHandler((exchange, request) -> {
                String tableName = (String) request.arguments().get("tableName");
                try {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(mcpService.getTableSchema(tableName))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                        .isError(true)
                        .build();
                }
            })
            .build());

        // 4. Read Query
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("read_query")
                .description("데이터 조회를 위한 SELECT SQL 실행")
                .inputSchema(new McpSchema.JsonSchema("object", 
                    Map.of("sql", Map.of("type", "string", "description", "실행할 SELECT 문")), 
                    List.of("sql"), false, null, null))
                .build())
            .callHandler((exchange, request) -> {
                String sql = (String) request.arguments().get("sql");
                try {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(mcpService.executeReadQuery(sql))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Query error: " + e.getMessage())))
                        .isError(true)
                        .build();
                }
            })
            .build());

        // 5. Write Query
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("write_query")
                .description("데이터 변경을 위한 SQL 실행 (CUD)")
                .inputSchema(new McpSchema.JsonSchema("object", 
                    Map.of("sql", Map.of("type", "string", "description", "실행할 CUD 문")), 
                    List.of("sql"), false, null, null))
                .build())
            .callHandler((exchange, request) -> {
                String sql = (String) request.arguments().get("sql");
                return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(mcpService.executeWriteQuery(sql))))
                    .isError(false)
                    .build();
            })
            .build());

        // 6. Explain Query
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("explain_query")
                .description("SQL의 실행 계획(Execution Plan)을 분석합니다.")
                .inputSchema(new McpSchema.JsonSchema("object", 
                    Map.of("sql", Map.of("type", "string", "description", "분석할 SQL 문")), 
                    List.of("sql"), false, null, null))
                .build())
            .callHandler((exchange, request) -> {
                String sql = (String) request.arguments().get("sql");
                try {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(mcpService.explainQuery(sql))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Explain error: " + e.getMessage())))
                        .isError(true)
                        .build();
                }
            })
            .build());
    }
}
