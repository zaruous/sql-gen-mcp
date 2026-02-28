package com.sqlgen.mcp.controller;

import com.sqlgen.mcp.handler.McpHandler;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.openapi.*;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class McpController {
    private static final Logger logger = LoggerFactory.getLogger(McpController.class);
    private final Map<String, SseBridgeSession> sessions = new ConcurrentHashMap<>();
    private final McpHandler mcpHandler;
    private final com.sqlgen.mcp.service.SchemaService schemaService;
    private final com.sqlgen.mcp.service.McpService mcpService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public McpController(McpHandler mcpHandler, com.sqlgen.mcp.service.SchemaService schemaService, com.sqlgen.mcp.service.McpService mcpService, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.mcpHandler = mcpHandler;
        this.schemaService = schemaService;
        this.mcpService = mcpService;
        this.objectMapper = objectMapper;
    }

    private static class SseBridgeSession {
        final McpSyncServer server;
        final McpServerSession session;
        final SseClient sseClient;

        SseBridgeSession(McpSyncServer server, McpServerSession session, SseClient sseClient) {
            this.server = server;
            this.session = session;
            this.sseClient = sseClient;
        }
    }

    private static class JavalinSseTransport implements McpServerTransport {
        private final SseClient sseClient;

        JavalinSseTransport(SseClient sseClient) {
            this.sseClient = sseClient;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    String json = McpJsonDefaults.getMapper().writeValueAsString(message);
                    logger.info("Sending SSE message: {}", json);
                    sseClient.sendEvent("message", json);
                } catch (Exception e) {
                    logger.error("Failed to send SSE message: {}", e.getMessage());
                }
            });
        }

        @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
        @Override public <T> T unmarshalFrom(Object o, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
            return McpJsonDefaults.getMapper().convertValue(o, typeRef);
        }
    }

    @OpenApi(path = "/sse", methods = HttpMethod.GET, summary = "MCP SSE connection")
    public void connectSse(SseClient sseClient) {
        String sessionId = sseClient.ctx().queryParam("sessionId");
        if (sessionId == null) {
            sseClient.close();
            return;
        }

        JavalinSseTransport transport = new JavalinSseTransport(sseClient);
        McpServerSession[] sessionHolder = new McpServerSession[1];
        McpServerTransportProvider provider = new McpServerTransportProvider() {
            @Override
            public void setSessionFactory(McpServerSession.Factory factory) {
                sessionHolder[0] = factory.create(transport);
            }
            @Override public List<String> protocolVersions() { return List.of("2024-11-05"); }
            @Override public Mono<Void> notifyClients(String method, Object params) { return Mono.empty(); }
            @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
        };

        McpSyncServer server = mcpHandler.createServer(provider);
        sessions.put(sessionId, new SseBridgeSession(server, sessionHolder[0], sseClient));
        
        logger.info("SSE Connection established for session: {}", sessionId);
        sseClient.onClose(() -> {
            sessions.remove(sessionId);
            server.close();
        });
    }

    @OpenApi(path = "/messages", methods = HttpMethod.POST, summary = "MCP Message input")
    public void handleMessages(Context ctx) {
        String body = ctx.body();
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null) sessionId = ctx.pathParamMap().get("sessionId");
        
        SseBridgeSession sessionWrapper = sessions.get(sessionId);
        if (sessionWrapper != null) {
            try {
                logger.info("Received Message ({}): {}", sessionId, body);
                
                // 수동 인터셉트 (최종 수단)
                if (body.contains("\"method\":\"initialize\"")) {
                    String id = extractId(body);
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"sql-gen-mcp\",\"version\":\"1.0.0\"}}}";
                    sessionWrapper.sseClient.sendEvent("message", response);
                    ctx.status(200).result("Accepted");
                    return;
                }

                if (body.contains("\"method\":\"tools/list\"")) {
                    String id = extractId(body);
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[" +
                        "{\"name\":\"get_table_list\",\"description\":\"DB 테이블 목록 및 코멘트 조회\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
                        "{\"name\":\"search_tables\",\"description\":\"키워드로 테이블 검색\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}," +
                        "{\"name\":\"get_table_schema\",\"description\":\"특정 테이블의 상세 스키마 조회\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"tableName\":{\"type\":\"string\"}},\"required\":[\"tableName\"]}}," +
                        "{\"name\":\"read_query\",\"description\":\"SELECT SQL 실행\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}}," +
                        "{\"name\":\"write_query\",\"description\":\"CUD SQL 실행\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}}" +
                        "]}}";
                    sessionWrapper.sseClient.sendEvent("message", response);
                    ctx.status(200).result("Accepted");
                    return;
                }

                Map<String, Object> rawMap = objectMapper.readValue(body, Map.class);
                McpSchema.JSONRPCMessage msg;
                if (rawMap.containsKey("method")) {
                    if (rawMap.containsKey("id")) {
                        msg = McpJsonDefaults.getMapper().convertValue(rawMap, McpSchema.JSONRPCRequest.class);
                    } else {
                        msg = McpJsonDefaults.getMapper().convertValue(rawMap, McpSchema.JSONRPCNotification.class);
                    }
                } else if (rawMap.containsKey("result") || rawMap.containsKey("error")) {
                    msg = McpJsonDefaults.getMapper().convertValue(rawMap, McpSchema.JSONRPCResponse.class);
                } else {
                    throw new RuntimeException("Unknown JSON-RPC message structure");
                }
                
                final String finalSessionId = sessionId;
                sessionWrapper.session.handle(msg).subscribe(
                    null, 
                    err -> logger.error("Async MCP handle error for session {}: {}", finalSessionId, err.getMessage())
                );
                ctx.status(200).result("Accepted");
            } catch (Exception e) {
                logger.error("Message handling error: {}", e.getMessage());
                ctx.status(500).result("error: " + e.getMessage());
            }
        } else {
            ctx.status(404).result("Session not found");
        }
    }

    private String extractId(String body) {
        int idIdx = body.indexOf("\"id\":");
        if (idIdx > 0) {
            int start = body.indexOf(":", idIdx) + 1;
            int end = body.indexOf(",", start);
            if (end < 0) end = body.indexOf("}", start);
            if (start > 0 && end > start) return body.substring(start, end).trim();
        }
        return "null";
    }

    @OpenApi(path = "/tables", methods = HttpMethod.GET, summary = "Get tables")
    public void getTableList(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.getTableList());
    }

    @OpenApi(path = "/tables/search", methods = HttpMethod.GET, summary = "Search tables")
    public void searchTables(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.searchTables(ctx.queryParam("q")));
    }

    @OpenApi(path = "/tables/{name}/schema", methods = HttpMethod.GET, summary = "Get table schema")
    public void getTableSchema(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.getTableSchema(ctx.pathParam("name")));
    }

    @OpenApi(path = "/query/read", methods = HttpMethod.POST, summary = "Read query")
    public void readQuery(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.executeReadQuery(ctx.body()));
    }

    @OpenApi(path = "/query/write", methods = HttpMethod.POST, summary = "Write query")
    public void writeQuery(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.executeWriteQuery(ctx.body()));
    }

    @OpenApi(path = "/schema/extract", methods = HttpMethod.POST, summary = "Extract schema")
    public void extractSchema(Context ctx) {
        schemaService.extractAndSave(ctx.queryParam("outputDir"));
        ctx.result("Schema extraction completed.");
    }

    @OpenApi(path = "/", methods = HttpMethod.GET, summary = "Status check")
    public void getIndex(Context ctx) {
        ctx.result("SQL MCP Server is running. SSE: /sse?sessionId={id}, POST: /messages?sessionId={id}");
    }
}
