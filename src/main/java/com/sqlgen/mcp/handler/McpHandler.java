package com.sqlgen.mcp.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlgen.mcp.service.McpService;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpHandler {
    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpService mcpService;

    public McpHandler(McpService mcpService) {
        this.mcpService = mcpService;
    }

    record Tool(String name, String description, Map<String, Object> properties) {
        public Map<String, Object> toMcpTool() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new ArrayList<>(properties.keySet()));
            return Map.of("name", name, "description", description, "inputSchema", schema);
        }
    }

    public void handle(SseClient sse, String body) {
        try {
            JsonNode request = mapper.readTree(body);
            String method = request.path("method").asText();
            JsonNode id = request.get("id");

            switch (method) {
                case "initialize" -> sse.sendEvent("message", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"SQL-MCP-Server\",\"version\":\"1.0.0\"}}}");
                case "tools/list" -> sendToolsList(sse, id);
                case "tools/call" -> handleToolCall(sse, id, request.get("params"));
                default -> logger.debug("Unhandled method: {}", method);
            }
        } catch (Exception e) {
            logger.error("Protocol error", e);
        }
    }

    private void sendToolsList(SseClient sse, JsonNode id) throws JsonProcessingException {
        List<Tool> tools = List.of(
            new Tool("get_table_list", "DB 테이블 목록 조회", Map.of()),
            new Tool("search_tables", "키워드로 테이블 검색", Map.of("query", Map.of("type", "string", "description", "키워드"))),
            new Tool("get_table_schema", "테이블 상세 정보 조회", Map.of("tableName", Map.of("type", "string"))),
            new Tool("read_query", "SELECT 실행", Map.of("sql", Map.of("type", "string"))),
            new Tool("write_query", "CUD 실행", Map.of("sql", Map.of("type", "string")))
        );

        List<Map<String, Object>> mcpTools = tools.stream().map(Tool::toMcpTool).toList();
        Map<String, Object> response = Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("tools", mcpTools));
        sse.sendEvent("message", mapper.writeValueAsString(response));
    }

    private void handleToolCall(SseClient sse, JsonNode id, JsonNode params) throws JsonProcessingException {
        String toolName = params.get("name").asText();
        JsonNode args = params.get("arguments");
        String resultData = switch (toolName) {
            case "get_table_list" -> mcpService.getTableList();
            case "search_tables" -> mcpService.searchTables(args.get("query").asText());
            case "get_table_schema" -> mcpService.getTableSchema(args.get("tableName").asText());
            case "read_query" -> mcpService.executeReadQuery(args.get("sql").asText());
            case "write_query" -> mcpService.executeWriteQuery(args.get("sql").asText());
            default -> "Unknown tool: " + toolName;
        };
        sse.sendEvent("message", toolCallResponse(id, resultData));
    }

    private String toolCallResponse(JsonNode id, String text) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id);
        ObjectNode result = root.putObject("result");
        ArrayNode contentArray = result.putArray("content");
        contentArray.addObject().put("type", "text").put("text", text);
        return mapper.writeValueAsString(root);
    }
}
