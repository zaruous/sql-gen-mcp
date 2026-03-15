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
    private final com.sqlgen.mcp.service.SchemaInitService schemaInitService;
    private final com.sqlgen.mcp.service.VectorStoreService vectorStoreService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public McpController(McpHandler mcpHandler, com.sqlgen.mcp.service.SchemaService schemaService, com.sqlgen.mcp.service.McpService mcpService, com.sqlgen.mcp.service.SchemaInitService schemaInitService, com.sqlgen.mcp.service.VectorStoreService vectorStoreService, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.mcpHandler = mcpHandler;
        this.schemaService = schemaService;
        this.mcpService = mcpService;
        this.schemaInitService = schemaInitService;
        this.vectorStoreService = vectorStoreService;
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
                    logger.info("Sending SSE message to client: {}", json);
                    
                    synchronized (sseClient) {
                        java.io.PrintWriter writer = sseClient.ctx().res().getWriter();
                        writer.write("event: message\n");
                        writer.write("data: " + json + "\n\n");
                        writer.flush();
                    }
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
        String sessionId = UUID.randomUUID().toString();
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
        
        // endpoint 이벤트 전송
        synchronized (sseClient) {
            try {
                String host = sseClient.ctx().header("Host");
                String scheme = sseClient.ctx().scheme();
                String endpointUrl = scheme + "://" + host + "/messages?sessionId=" + sessionId;
                
                java.io.PrintWriter writer = sseClient.ctx().res().getWriter();
                writer.write("event: endpoint\n");
                writer.write("data: " + endpointUrl + "\n\n");
                writer.flush();
                logger.info("New MCP SSE Session created: {}, endpoint={}", sessionId, endpointUrl);
            } catch (Exception e) {
                logger.error("Failed to send endpoint event", e);
            }
        }

        sseClient.onClose(() -> {
            sessions.remove(sessionId);
            server.close();
        });
    }

    @OpenApi(path = "/messages", methods = HttpMethod.POST, summary = "MCP Message input")
    public void handleMessages(Context ctx) {
        String body = ctx.body();
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null) {
            sessionId = ctx.pathParamMap().get("sessionId");
        }
        
        if (sessionId == null) {
            ctx.status(400).result("{\"error\":\"Missing sessionId\"}");
            return;
        }

        SseBridgeSession sessionWrapper = sessions.get(sessionId);
        if (sessionWrapper != null) {
            try {
                logger.info("McpController.handleMessages (Session: {}): body={}", sessionId, body);
                
                // 수동 문자열 검사를 통한 initialize 및 tools/list 처리
                if (body.contains("\"method\":\"initialize\"")) {
                    String id = extractId(body);
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"sql-gen-mcp\",\"version\":\"1.0.0\"}}}";
                    
                    synchronized (sessionWrapper.sseClient) {
                        java.io.PrintWriter writer = sessionWrapper.sseClient.ctx().res().getWriter();
                        writer.write("event: message\n");
                        writer.write("data: " + response + "\n\n");
                        writer.flush();
                    }
                    logger.info("Sent manual initialize response for session {}", sessionId);
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
                        "{\"name\":\"write_query\",\"description\":\"CUD SQL 실행\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}}," +
                        "{\"name\":\"explain_query\",\"description\":\"SQL 실행 계획 조회\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}}," +
                        "{\"name\":\"search_knowledge_base\",\"description\":\"자연어로 테이블 정의서 검색\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}" +
                        "]}}";
                    
                    synchronized (sessionWrapper.sseClient) {
                        java.io.PrintWriter writer = sessionWrapper.sseClient.ctx().res().getWriter();
                        writer.write("event: message\n");
                        writer.write("data: " + response + "\n\n");
                        writer.flush();
                    }
                    logger.info("Sent manual tools/list response for session {}", sessionId);
                    ctx.status(200).result("Accepted");
                    return;
                }

                // 3. explain_query 호출 인터셉트
                if (body.contains("\"method\":\"tools/call\"") && body.contains("\"name\":\"explain_query\"")) {
                    String id = extractId(body);
                    int sqlIdx = body.indexOf("\"sql\":");
                    String sql = "";
                    if (sqlIdx > 0) {
                        int start = body.indexOf("\"", sqlIdx + 6) + 1;
                        int end = body.indexOf("\"", start);
                        if (start > 0 && end > start) sql = body.substring(start, end).replace("\\\"", "\"");
                    }
                    
                    final String finalSql = sql;
                    final String finalId = id;
                    Mono.fromCallable(() -> mcpService.explainQuery(finalSql))
                        .subscribe(result -> {
                            try {
                                String escapedResult = result.replace("\"", "\\\"");
                                String response = "{\"jsonrpc\":\"2.0\",\"id\":" + finalId + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + escapedResult + "\"}]}}";
                                synchronized (sessionWrapper.sseClient) {
                                    java.io.PrintWriter writer = sessionWrapper.sseClient.ctx().res().getWriter();
                                    writer.write("event: message\ndata: " + response + "\n\n");
                                    writer.flush();
                                }
                            } catch (Exception e) {
                                logger.error("Explain fail", e);
                            }
                        });
                    ctx.status(200).result("Accepted");
                    return;
                }

                // 4. search_knowledge_base 호출 인터셉트
                if (body.contains("\"method\":\"tools/call\"") && body.contains("\"name\":\"search_knowledge_base\"")) {
                    String id = extractId(body);
                    int queryIdx = body.indexOf("\"query\":");
                    String query = "";
                    if (queryIdx > 0) {
                        int start = body.indexOf("\"", queryIdx + 8) + 1;
                        int end = body.indexOf("\"", start);
                        if (start > 0 && end > start) query = body.substring(start, end).replace("\\\"", "\"");
                    }
                    
                    final String finalQuery = query;
                    final String finalId = id;
                    Mono.fromCallable(() -> vectorStoreService.search(finalQuery, 5))
                        .subscribe(results -> {
                            try {
                                String joined = String.join("\\n---\\n", results).replace("\"", "\\\"");
                                String response = "{\"jsonrpc\":\"2.0\",\"id\":" + finalId + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + joined + "\"}]}}";
                                synchronized (sessionWrapper.sseClient) {
                                    java.io.PrintWriter writer = sessionWrapper.sseClient.ctx().res().getWriter();
                                    writer.write("event: message\ndata: " + response + "\n\n");
                                    writer.flush();
                                }
                            } catch (Exception e) {
                                logger.error("Knowledge search fail", e);
                            }
                        });
                    ctx.status(200).result("Accepted");
                    return;
                }

                if (body.contains("\"method\":\"notifications/initialized\"")) {
                    ctx.status(200).result("Accepted");
                    return;
                }

                // 일반 메시지는 SDK에 위임
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
            logger.warn("Session not found: {}", sessionId);
            ctx.status(404).result("Session not found");
        }
    }

    // ── Streamable HTTP Transport (MCP 2025-03-26) ───────────────────────────────
    @OpenApi(path = "/mcp", methods = HttpMethod.POST, summary = "MCP Streamable HTTP endpoint")
    public void handleStreamable(Context ctx) {
        String body = ctx.body();
        try {
            Map<String, Object> rawMap = objectMapper.readValue(body, Map.class);
            String method = (String) rawMap.get("method");
            Object id = rawMap.get("id");
            String idJson = objectMapper.writeValueAsString(id);

            logger.info("Streamable MCP request: method={}", method);

            if ("initialize".equals(method)) {
                String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{"
                        + "\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{\"tools\":{\"listChanged\":true}},"
                        + "\"serverInfo\":{\"name\":\"sql-gen-mcp\",\"version\":\"1.1.0\"}}}";
                ctx.contentType("application/json").status(200).result(resp);
                return;
            }

            if ("notifications/initialized".equals(method)) {
                ctx.status(202).result("");
                return;
            }

            if ("tools/list".equals(method)) {
                String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{\"tools\":["
                        + "{\"name\":\"get_table_list\",\"description\":\"DB 테이블 목록 및 코멘트 조회\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},"
                        + "{\"name\":\"search_tables\",\"description\":\"키워드로 테이블 검색\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}},"
                        + "{\"name\":\"get_table_schema\",\"description\":\"특정 테이블의 상세 스키마 조회\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"tableName\":{\"type\":\"string\"}},\"required\":[\"tableName\"]}},"
                        + "{\"name\":\"read_query\",\"description\":\"SELECT SQL 실행\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}},"
                        + "{\"name\":\"write_query\",\"description\":\"CUD SQL 실행\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}},"
                        + "{\"name\":\"explain_query\",\"description\":\"SQL 실행 계획 조회\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}},"
                        + "{\"name\":\"search_knowledge_base\",\"description\":\"자연어로 테이블 정의서 검색\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}"
                        + "]}}";
                ctx.contentType("application/json").status(200).result(resp);
                return;
            }

            if ("tools/call".equals(method)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) rawMap.get("params");
                String toolName = params != null ? (String) params.get("name") : null;
                @SuppressWarnings("unchecked")
                Map<String, Object> args = params != null ? (Map<String, Object>) params.get("arguments") : Map.of();
                if (args == null) args = Map.of();

                String toolResult;
                try {
                    toolResult = switch (toolName != null ? toolName : "") {
                        case "get_table_list"       -> mcpService.getTableList();
                        case "search_tables"        -> mcpService.searchTables((String) args.get("query"));
                        case "get_table_schema"     -> mcpService.getTableSchema((String) args.get("tableName"));
                        case "read_query"           -> mcpService.executeReadQuery((String) args.get("sql"));
                        case "write_query"          -> mcpService.executeWriteQuery((String) args.get("sql"));
                        case "explain_query"        -> mcpService.explainQuery((String) args.get("sql"));
                        case "search_knowledge_base"-> String.join("\n---\n", vectorStoreService.search((String) args.get("query"), 5));
                        default                     -> "Unknown tool: " + toolName;
                    };
                } catch (Exception e) {
                    toolResult = "Error: " + e.getMessage();
                }

                String escapedResult = objectMapper.writeValueAsString(toolResult);
                String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":" + escapedResult + "}],\"isError\":false}}";
                ctx.contentType("application/json").status(200).result(resp);
                return;
            }

            ctx.contentType("application/json").status(200).result(
                    "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":-32601,\"message\":\"Method not found: " + method + "\"}}");

        } catch (Exception e) {
            logger.error("Streamable MCP error: {}", e.getMessage());
            ctx.status(500).result("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + e.getMessage() + "\"}}");
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

    @OpenApi(path = "/tables", methods = HttpMethod.GET, summary = "Get tables", 
        description = "Returns list of all tables in the database with their comments")
    public void getTableList(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.getTableList());
    }

    @OpenApi(path = "/tables/search", methods = HttpMethod.GET, summary = "Search tables",
        queryParams = {
            @OpenApiParam(name = "q", description = "Keyword to search for in table names", required = true)
        })
    public void searchTables(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.searchTables(ctx.queryParam("q")));
    }

    @OpenApi(path = "/tables/{name}/schema", methods = HttpMethod.GET, summary = "Get table schema",
        pathParams = {
            @OpenApiParam(name = "name", description = "Target table name", required = true)
        })
    public void getTableSchema(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.getTableSchema(ctx.pathParam("name")));
    }

    @OpenApi(path = "/query/read", methods = HttpMethod.POST, summary = "Read query",
        requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = String.class), description = "SELECT SQL statement", required = true))
    public void readQuery(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.executeReadQuery(ctx.body()));
    }

    @OpenApiIgnore
    @OpenApi(path = "/query/write", methods = HttpMethod.POST, summary = "Write query",
        requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = String.class), description = "INSERT/UPDATE/DELETE SQL statement", required = true))
    public void writeQuery(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.executeWriteQuery(ctx.body()));
    }

    @OpenApi(path = "/query/explain", methods = HttpMethod.POST, summary = "Explain query",
        requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = String.class), description = "SQL statement to explain", required = true))
    public void explainQuery(Context ctx) throws Exception {
        ctx.contentType("application/json").result(mcpService.explainQuery(ctx.body()));
    }

    @OpenApi(path = "/knowledge/search", methods = HttpMethod.GET, summary = "Search knowledge base (RAG)",
        queryParams = {
            @OpenApiParam(name = "q", description = "Natural language query", required = true)
        })
    public void searchKnowledge(Context ctx) throws Exception {
        String query = ctx.queryParam("q");
        List<String> results = vectorStoreService.search(query, 10);
        ctx.contentType("application/json").result(objectMapper.writeValueAsString(Map.of("results", results)));
    }

    @OpenApi(path = "/schema/extract", methods = HttpMethod.POST, summary = "Extract schema")
    public void extractSchema(Context ctx) {
        schemaService.extractAndSave(ctx.queryParam("outputDir"));
        ctx.result("Schema extraction completed.");
    }

    @OpenApi(
        path = "/db/initializeSchema",
        methods = HttpMethod.POST,
        summary = "Database Initialization (Schema extraction)",
        queryParams = {
            @OpenApiParam(name = "outputDir", description = "Output directory for schema files", type = String.class)
        }
    )
    public void initializeSchema(Context ctx) {
        boolean success = schemaInitService.initializeSchema(ctx.queryParam("outputDir"));
        if (success) {
            ctx.result("Database initialization completed successfully.");
        } else {
            ctx.status(500).result("Database initialization failed.");
        }
    }

    @OpenApi(path = "/", methods = HttpMethod.GET, summary = "Status check")
    public void getIndex(Context ctx) {
        ctx.result("SQL MCP Server is running. SSE: /sse?sessionId={id}, POST: /messages?sessionId={id}");
    }
}
