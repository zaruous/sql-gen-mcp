package com.sqlgen.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

@Configuration
@ComponentScan(basePackages = "com.sqlgen.mcp")
public class McpServer {
    private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

    @org.springframework.context.annotation.Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    public static void main(String[] args) {
        boolean stdioMode = false;
        int portFromArgs = -1;

        for (String arg : args) {
            if ("--stdio".equals(arg)) {
                stdioMode = true;
            } else {
                try {
                    portFromArgs = Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {}
            }
        }

        McpServer serverApp = new McpServer();
        if (stdioMode) {
            serverApp.launchStdio();
        } else {
            serverApp.launch(portFromArgs);
        }
    }

    public void launchStdio() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(McpServer.class);
        com.sqlgen.mcp.handler.McpHandler mcpHandler = context.getBean(com.sqlgen.mcp.handler.McpHandler.class);
        
        // Stdio 트랜스포트 프로바이더로 서버 시작
        StdioServerTransportProvider transportProvider = 
            new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        
        mcpHandler.createServer(transportProvider);
        
        logger.info("SQL MCP Server started in STDIO mode.");
        // Stdio 서버는 SDK 내부에서 Reactor를 통해 입력을 감시하므로 별도의 루프가 필요할 수 있습니다.
        // 하지만 StdioMcpSessionTransport가 데몬 스레드가 아니라면 JVM은 유지됩니다.
    }

    public void launch(int portOverride) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(McpServer.class);
        com.sqlgen.mcp.controller.McpController mcpController = context.getBean(com.sqlgen.mcp.controller.McpController.class);
        
        com.fasterxml.jackson.databind.ObjectMapper yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        int port = 7070;
        
        java.io.File externalConfig = new java.io.File("application.yml");
        try (java.io.InputStream is = externalConfig.exists() 
                ? new java.io.FileInputStream(externalConfig) 
                : McpServer.class.getClassLoader().getResourceAsStream("application.yml")) {
            
            if (is != null) {
                com.fasterxml.jackson.databind.JsonNode root = yamlMapper.readTree(is);
                if (root.has("mcp") && root.path("mcp").has("port")) {
                    port = root.path("mcp").path("port").asInt(7070);
                } else {
                    port = root.path("server").path("port").asInt(7070);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read application.yml, using default port 7070");
        }

        if (portOverride > 0) {
            port = portOverride;
        }

        final int finalPort = port;
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            config.registerPlugin(new OpenApiPlugin(openApiConfig -> {
                openApiConfig.withDocumentationPath("/openapi.json");
                openApiConfig.withDefinitionConfiguration((version, definition) -> {
                    definition.withInfo(info -> {
                        info.setTitle("SQL MCP Server API");
                        info.setVersion("1.1.0");
                    });
                });
            }));
            config.registerPlugin(new SwaggerPlugin(swaggerConfig -> {}));
        }).start(finalPort);

        app.get("/", mcpController::getIndex);
        app.sse("/sse", mcpController::connectSse);
        app.post("/messages", mcpController::handleMessages);
        app.post("/messages/{sessionId}", mcpController::handleMessages);
        
        // Table & Query APIs
        app.get("/tables", mcpController::getTableList);
        app.get("/tables/search", mcpController::searchTables);
        app.get("/tables/{name}/schema", mcpController::getTableSchema);
        app.post("/query/read", mcpController::readQuery);
        app.post("/query/write", mcpController::writeQuery);
        app.post("/query/explain", mcpController::explainQuery);
        app.get("/knowledge/search", mcpController::searchKnowledge);
        app.post("/schema/extract", mcpController::extractSchema);
        app.post("/db/initializeSchema", mcpController::initializeSchema);

        logger.info("SQL MCP Server started on port {}. Swagger: http://localhost:{}/swagger", finalPort, finalPort);
    }
}
