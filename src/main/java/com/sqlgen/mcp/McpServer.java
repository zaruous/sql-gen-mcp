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
        new McpServer().launch();
    }

    public void launch() {
        com.fasterxml.jackson.databind.ObjectMapper yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        int port = 7070; // 기본값

        try (var is = McpServer.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (is != null) {
                com.fasterxml.jackson.databind.JsonNode root = yamlMapper.readTree(is);
                port = root.path("server").path("port").asInt(7070);
            }
        } catch (Exception e) {
            logger.warn("Failed to read port from application.yml, using default 7070");
        }

        final int finalPort = port;
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(McpServer.class);
        com.sqlgen.mcp.controller.McpController mcpController = context.getBean(com.sqlgen.mcp.controller.McpController.class);

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
        }).start(finalPort);

        app.get("/", mcpController::getIndex);
        app.sse("/sse", mcpController::connectSse);
        app.post("/messages", mcpController::handleMessages);

        logger.info("SQL MCP Server started on port {}. Swagger: http://localhost:{}/swagger", finalPort, finalPort);
    }
}
