package com.sqlgen.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.FileInputStream;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@Configuration
public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    private java.io.InputStream getConfigurationStream() throws java.io.IOException {
        java.io.File externalConfig = new java.io.File("application.yml");
        if (externalConfig.exists()) {
            logger.info("Using external configuration from application.yml");
            return new java.io.FileInputStream(externalConfig);
        }
        return DatabaseConfig.class.getClassLoader().getResourceAsStream("application.yml");
    }

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        
        try (var is = getConfigurationStream()) {
            JsonNode dbNode = null;
            if (is != null) {
                dbNode = yamlMapper.readTree(is).path("db");
            }

            // 환경 변수 우선 적용 (Docker Desktop UI에서 확인 및 수정 가능)
            String driver = getEnvOrYaml(dbNode, "driver", "DB_DRIVER", "org.postgresql.Driver");
            String url = getEnvOrYaml(dbNode, "url", "DB_URL", "jdbc:postgresql://localhost:5432/postgres");
            String user = getEnvOrYaml(dbNode, "user", "DB_USER", "postgres");
            String pw = getEnvOrYaml(dbNode, "pw", "DB_PW", "password");

            dataSource.setDriverClassName(driver);
            dataSource.setUrl(url);
            dataSource.setUsername(user);
            dataSource.setPassword(pw);
            
            logger.info("Database Connection Info: URL={}, Driver={}", url, driver);
        } catch (Exception e) {
            logger.error("Error loading database configuration", e);
        }
        return dataSource;
    }

    private String getEnvOrYaml(JsonNode dbNode, String yamlKey, String envKey, String defaultValue) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        if (dbNode != null && dbNode.has(yamlKey)) {
            return dbNode.path(yamlKey).asText();
        }
        return defaultValue;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public com.sqlgen.mcp.service.SchemaService schemaService(DataSource dataSource) {
        com.sqlgen.mcp.service.SchemaService service = new com.sqlgen.mcp.service.SchemaService(dataSource);
        ObjectMapper yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        try (var is = getConfigurationStream()) {
            if (is != null) {
                JsonNode root = yamlMapper.readTree(is);
                String outputDir = root.path("db").path("schema-output-dir").asText();
                if (outputDir != null && !outputDir.isEmpty()) {
                    service.setDefaultOutputDir(outputDir);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not set default output dir from application.yml");
        }
        return service;
    }
}
