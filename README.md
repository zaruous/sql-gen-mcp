# SQL MCP Server (Maven + Spring + Javalin)

Maven 기반의 깔끔한 패키지 구조로 재정의된 SQL MCP 서버입니다.

## 프로젝트 구조
- `com.sqlgen.mcp`: 메인 서버 엔트리 포인트 (`McpServer`)
- `com.sqlgen.mcp.config`: Spring 설정 (`DatabaseConfig`)
- `com.sqlgen.mcp.handler`: MCP 프로토콜 및 도구 핸들링 (`McpHandler`)
- `com.sqlgen.mcp.service`: 비즈니스 로직 및 DB 연동 (`McpService`, `SchemaService`)

## 설정
1. `src/main/resources/db_connection.properties`에서 DB 정보를 수정합니다.
2. `mvn clean compile` 명령어로 빌드합니다.

## 실행
`com.sqlgen.mcp.McpServer` 클래스의 `main` 메서드를 실행합니다.

## 주요 도구 (Tools)
- **get_table_list**: 전체 테이블 목록 조회
- **search_tables**: 키워드 검색
- **get_table_schema**: 상세 컬럼 정보 조회
- **read_query**: SELECT 쿼리 실행
- **write_query**: INSERT/UPDATE/DELETE 실행
