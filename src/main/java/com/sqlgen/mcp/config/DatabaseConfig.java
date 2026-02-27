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

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        
        try (var is = DatabaseConfig.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (is != null) {
                JsonNode root = yamlMapper.readTree(is);
                JsonNode db = root.path("db");
                
                dataSource.setDriverClassName(db.path("driver").asText("org.postgresql.Driver"));
                dataSource.setUrl(db.path("url").asText("jdbc:postgresql://localhost:5432/postgres"));
                dataSource.setUsername(db.path("user").asText("postgres"));
                dataSource.setPassword(db.path("pw").asText("password"));
                
                logger.info("Loaded DB config from application.yml: {}", dataSource.getUrl());
            } else {
                logger.warn("application.yml not found, using defaults.");
                dataSource.setDriverClassName("org.postgresql.Driver");
                dataSource.setUrl("jdbc:postgresql://localhost:5432/postgres");
                dataSource.setUsername("postgres");
                dataSource.setPassword("password");
            }
        } catch (Exception e) {
            logger.error("Error loading application.yml", e);
        }
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public com.sqlgen.mcp.service.SchemaService schemaService(DataSource dataSource) {
        com.sqlgen.mcp.service.SchemaService service = new com.sqlgen.mcp.service.SchemaService(dataSource);
        ObjectMapper yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        try (var is = DatabaseConfig.class.getClassLoader().getResourceAsStream("application.yml")) {
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
