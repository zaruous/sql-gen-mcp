package com.sqlgen.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DB 스키마 정보를 추출하여 JSON 및 마크다운 파일로 초기화하는 서비스
 * SchemaInitApplication.java의 기능을 API로 제공하기 위해 추가됨
 */
@Service
public class SchemaInitService {
    private static final Logger logger = LoggerFactory.getLogger(SchemaInitService.class);
    private final SchemaService schemaService;

    public SchemaInitService(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    /**
     * 데이터베이스 스키마 정보를 추출하고 파일로 저장합니다.
     * 
     * @param outputDir 출력 디렉토리 (null일 경우 기본값 docs/schema)
     * @return 성공 여부
     */
    public boolean initializeSchema(String outputDir) {
        logger.info("Initializing Schema Extraction and Saving (Database Initialization)...");
        try {
            return schemaService.extractAndSave(outputDir);
        } catch (Exception e) {
            logger.error("Failed to initialize schema", e);
            return false;
        }
    }
}
