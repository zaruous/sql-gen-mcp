package com.sqlgen.mcp;

import com.sqlgen.mcp.handler.McpHandler;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.OpenApiPluginConfiguration;
import io.javalin.openapi.plugin.swagger.SwaggerConfiguration;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.javalin.openapi.OpenApiInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@ComponentScan(basePackages = "com.sqlgen.mcp")
public class McpServer {
    private static final Logger logger = LoggerFactory.getLogger(McpServer.class);
    private final Map<String, SseClient> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        String port = System.getProperty("server.port", "7070");
        new McpServer().launch(port);
    }

    public void launch(String serverPort) {
        int port = Integer.parseInt(serverPort);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(McpServer.class);
        McpHandler mcpHandler = context.getBean(McpHandler.class);

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            
            // Javalin 6 Virtual Threads simplification
            config.jetty.threadPool = new org.eclipse.jetty.util.thread.QueuedThreadPool();

            config.registerPlugin(new OpenApiPlugin(openApiConfig -> {
                openApiConfig.withDefinitionConfiguration((version, definition) -> {
                    definition.withOpenApiInfo(info -> {
                        info.setTitle("SQL MCP Server API");
                        info.setVersion("1.0.0");
                    });
                });
            }));
            config.registerPlugin(new SwaggerPlugin(swaggerConfig -> {}));
        }).start(port);

        app.get("/", ctx -> ctx.result("SQL MCP Server is running. Visit /swagger for API docs."));

        app.sse("/sse", sseClient -> {
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, sseClient);
            String endpointUrl = "http://localhost:" + port + "/messages?sessionId=" + sessionId;
            sseClient.sendEvent("endpoint", endpointUrl);
            logger.info("New MCP Client Connected: {}", sessionId);
            sseClient.onClose(() -> sessions.remove(sessionId));
        });

        app.post("/messages", ctx -> {
            String sessionId = ctx.queryParam("sessionId");
            String body = ctx.body();
            SseClient sse = sessions.get(sessionId);
            if (sse != null) mcpHandler.handle(sse, body);
            ctx.status(200).result("OK");
        });

        logger.info("SQL MCP Server started on port {}. Swagger: http://localhost:{}/swagger", port, port);
    }
}
