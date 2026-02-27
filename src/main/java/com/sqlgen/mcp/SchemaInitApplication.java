package com.sqlgen.mcp;

import com.sqlgen.mcp.config.DatabaseConfig;
import com.sqlgen.mcp.service.SchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * DB 스키마 정보를 추출하여 JSON 및 마크다운 파일로 초기화하는 도구
 */
public class SchemaInitApplication {
    private static final Logger logger = LoggerFactory.getLogger(SchemaInitApplication.class);

    public static void main(String[] args) {
        logger.info("Starting Schema Initialization...");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(DatabaseConfig.class)) {
            SchemaService schemaService = context.getBean(SchemaService.class);
            
            // 기본 출력 디렉토리(docs/schema) 또는 실행 인자에서 받은 경로로 추출 실행
            String outputDir = args.length > 0 ? args[0] : null;
            
            boolean success = schemaService.extractAndSave(outputDir);
            
            if (success) {
                logger.info("Schema extraction and saving completed successfully.");
            } else {
                logger.error("Schema extraction failed. Please check the database connection and logs.");
                System.exit(1);
            }
        } catch (Exception e) {
            logger.error("Error during schema initialization", e);
            System.exit(1);
        }
    }
}
