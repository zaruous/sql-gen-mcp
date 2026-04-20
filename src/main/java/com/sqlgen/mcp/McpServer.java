package com.sqlgen.mcp;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.sqlgen.mcp.admin.ToolAdminController;
import com.sqlgen.mcp.service.SchemaInitService;

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
        // --key=value 형태의 커맨드라인 인수를 System property로 등록
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                System.setProperty(parts[0], parts[1]);
            }
        }
    	
        boolean stdioMode = false;
        int portFromArgs = -1;

        for (String arg : args) {
            if ("--stdio".equals(arg)) {
                stdioMode = true;
            } else if (!arg.startsWith("--")) {
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

    public AnnotationConfigApplicationContext createSpringContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        
        // Load application.yml into Spring Environment
        try {
            java.io.File externalConfig = new java.io.File("application.yml");
            java.util.Properties props = new java.util.Properties();
            
            com.fasterxml.jackson.databind.ObjectMapper yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            
            try (java.io.InputStream is = externalConfig.exists() 
                    ? new java.io.FileInputStream(externalConfig) 
                    : McpServer.class.getClassLoader().getResourceAsStream("application.yml")) {
                
                if (is != null) {
                    com.fasterxml.jackson.databind.JsonNode root = yamlMapper.readTree(is);
                    addFlattenedProperties(props, "", root);
                }
            }
            
            org.springframework.core.env.PropertiesPropertySource source = new org.springframework.core.env.PropertiesPropertySource("appConfig", props);
            context.getEnvironment().getPropertySources().addLast(source);
            
        } catch (Exception e) {
            logger.warn("Failed to load application.yml into Spring Environment: {}", e.getMessage());
        }

        context.register(McpServer.class);
        context.refresh();
        return context;
    }

    private void addFlattenedProperties(java.util.Properties props, String prefix, com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
                addFlattenedProperties(props, prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), entry.getValue());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                addFlattenedProperties(props, prefix + "[" + i + "]", node.get(i));
            }
        } else {
            props.put(prefix, node.asText());
        }
    }
//    public void initData() throws FileNotFoundException, IOException {
//    	
//    	Properties p = new Properties();
//    	try(FileInputStream inStream = new FileInputStream("db.properties")){
//    		p.load(inStream);
//    	}
//    	
//		
//    }
    
    public void launchStdio() {
        AnnotationConfigApplicationContext context = createSpringContext();
        com.sqlgen.mcp.handler.McpHandler mcpHandler = context.getBean(com.sqlgen.mcp.handler.McpHandler.class);

        SchemaInitService schemaInitService = context.getBean(SchemaInitService.class);
        schemaInitService.initializeSchema(null);
        logger.info("Schema initialized.");

        // Stdio 트랜스포트 프로바이더로 서버 시작
        StdioServerTransportProvider transportProvider = 
            new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        
        mcpHandler.createServer(transportProvider);
        
        
        
        logger.info("SQL MCP Server started in STDIO mode.");
    }

    public void launch(int portOverride) {
        AnnotationConfigApplicationContext context = createSpringContext();
        com.sqlgen.mcp.controller.McpController mcpController = context.getBean(com.sqlgen.mcp.controller.McpController.class);
        com.fasterxml.jackson.databind.ObjectMapper sharedObjectMapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);

        SchemaInitService schemaInitService = context.getBean(SchemaInitService.class);
        schemaInitService.initializeSchema(null);
        logger.info("Schema initialized.");

        int port = 7070;
        try {
            port = Integer.parseInt(context.getEnvironment().getProperty("mcp.port", 
                   context.getEnvironment().getProperty("server.port", "7070")));
        } catch (Exception ignored) {}

        if (portOverride > 0) {
            port = portOverride;
        }

        final int finalPort = port;
        ToolAdminController toolAdminController = context.getBean(ToolAdminController.class);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new io.javalin.json.JavalinJackson(sharedObjectMapper, false));
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory  = "/static";
                staticFiles.location   = io.javalin.http.staticfiles.Location.CLASSPATH;
            });
            config.registerPlugin(new OpenApiPlugin(openApiConfig -> {
                openApiConfig.withDocumentationPath("/openapi.json");
                openApiConfig.withDefinitionConfiguration((version, definition) -> {
                    definition.withInfo(info -> {
                        info.setTitle("SQL MCP Server API");
                        info.setVersion("1.1.0");
                    });
                });
            }));
            config.registerPlugin(new SwaggerPlugin(swaggerConfig -> {
                swaggerConfig.setDocumentationPath("/openapi.json");
            }));
        }).start(finalPort);

        // API Compatibility: Redirect /openapi to /openapi.json
        app.get("/openapi", ctx -> ctx.redirect("/openapi.json"));

        // Admin Web UI + API
        app.get("/api/tools",                  toolAdminController::listTools);
        app.get("/api/tools/search",           toolAdminController::search);
        app.get("/api/tools/status",           toolAdminController::status);
        app.post("/api/tools/{name}/boost",    toolAdminController::setBoost);
        app.delete("/api/tools/{name}/boost",  toolAdminController::removeBoost);
        app.get("/api/tools/metadata/export",  toolAdminController::exportMetadata);
        app.post("/api/tools/metadata/import", toolAdminController::importMetadata);

        app.get("/", mcpController::getIndex);
        app.sse("/sse", mcpController::connectSse);
        app.post("/messages", mcpController::handleMessages);
        app.post("/messages/{sessionId}", mcpController::handleMessages);
        // Streamable HTTP transport (MCP 2025-03-26)
        app.post("/mcp", mcpController::handleStreamable);
        
        // Table & Query APIs
        app.get("/tables", mcpController::getTableList);
        app.get("/tables/search", mcpController::searchTables);
        app.get("/tables/{name}/schema", mcpController::getTableSchema);
        app.post("/query/read", mcpController::readQuery);
//        app.post("/query/write", mcpController::writeQuery);
        app.post("/query/explain", mcpController::explainQuery);
        app.get("/knowledge/search", mcpController::searchKnowledge);
        app.post("/schema/extract", mcpController::extractSchema);
        app.post("/db/initializeSchema", mcpController::initializeSchema);

        logger.info("SQL MCP Server started on port {}. Swagger: http://localhost:{}/swagger", finalPort, finalPort);
    }
}
