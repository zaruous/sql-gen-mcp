package com.sqlgen.mcp.controller;

import com.sqlgen.mcp.handler.McpHandler;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.openapi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class McpController {
    private static final Logger logger = LoggerFactory.getLogger(McpController.class);
    private final Map<String, SseClient> sessions = new ConcurrentHashMap<>();
    private final McpHandler mcpHandler;
    private final com.sqlgen.mcp.service.SchemaService schemaService;

    public McpController(McpHandler mcpHandler, com.sqlgen.mcp.service.SchemaService schemaService) {
        this.mcpHandler = mcpHandler;
        this.schemaService = schemaService;
    }

    @OpenApi(
        path = "/schema/extract",
        methods = HttpMethod.POST,
        summary = "Extract database schema and save to files",
        queryParams = @OpenApiParam(name = "outputDir", description = "Optional override for output directory"),
        responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = String.class))
    )
    public void extractSchema(Context ctx) {
        String outputDir = ctx.queryParam("outputDir");
        // null이면 서비스 내부 로직이나 설정에서 처리하도록 위임 (여기서는 컨트롤러에서 주입 가능하도록 설계)
        schemaService.extractAndSave(outputDir);
        ctx.result("Schema extraction completed.");
    }

    @OpenApi(
        path = "/",
        methods = HttpMethod.GET,
        summary = "Server status check",
        responses = @OpenApiResponse(status = "200")
    )
    public void getIndex(Context ctx) {
        ctx.result("SQL MCP Server is running. Visit /swagger for API docs.");
    }

    @OpenApi(
        path = "/sse",
        methods = HttpMethod.GET,
        summary = "MCP SSE connection endpoint",
        responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = String.class))
    )
    public void connectSse(SseClient sseClient) {
        String port = String.valueOf(sseClient.ctx().port());
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, sseClient);
        
        String endpointUrl = "http://localhost:" + port + "/messages?sessionId=" + sessionId;
        sseClient.sendEvent("endpoint", endpointUrl);
        
        logger.info("New MCP Client Connected: {}", sessionId);
        sseClient.onClose(() -> sessions.remove(sessionId));
    }

    @OpenApi(
        path = "/messages",
        methods = HttpMethod.POST,
        summary = "MCP Message handler",
        queryParams = @OpenApiParam(name = "sessionId", required = true),
        requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = String.class)),
        responses = @OpenApiResponse(status = "200")
    )
    public void handleMessages(Context ctx) {
        String sessionId = ctx.queryParam("sessionId");
        String body = ctx.body();
        SseClient sse = sessions.get(sessionId);
        if (sse != null) {
            mcpHandler.handle(sse, body);
        }
        ctx.status(200).result("OK");
    }
}
