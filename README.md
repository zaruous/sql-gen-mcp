# SQL MCP Server (Java & Javalin)

이 프로젝트는 **Model Context Protocol (MCP)**을 구현한 SQL 데이터베이스 인터페이스 서버입니다. 
Claude와 같은 AI 에이전트가 데이터베이스 스키마를 탐색하고, 안전하게 쿼리를 실행하며, 테이블 구조를 분석할 수 있도록 돕습니다.

---

## 🚀 주요 기능
- **MCP 표준 지원 (SSE):** Claude Desktop 등 공식 MCP 클라이언트와 완벽하게 호환됩니다.
- **Stateless HTTP RPC:** 상태 비유지 방식의 표준 HTTP POST 호출을 지원합니다.
- **멀티 DBMS 지원:** PostgreSQL, Oracle, MSSQL 등 다양한 데이터베이스와 연동 가능합니다.
- **DB 탐색 도구:** 테이블 목록 조회, 키워드 검색, 상세 스키마 추출 기능을 제공합니다.
- **SQL 실행:** 읽기 전용(Read-only) 및 쓰기(Write) 쿼리를 구분하여 안전하게 실행합니다.
- **Swagger UI:** `http://localhost:7070/swagger`를 통해 API를 직접 테스트할 수 있습니다.

---

## 🛠️ 설치 및 빌드

### 필수 요구사항
- **Java 17** 이상
- **Maven 3.8** 이상
- 연동할 데이터베이스 (PostgreSQL, Oracle, MSSQL 등)

### 빌드 방법
```bash
git clone <repository-url>
cd sql-gen-mcp
mvn clean package
```

---

## ⚙️ 설정 (Configuration)

실행 디렉토리에 `application.yml` 파일을 생성하거나 수정하여 서버 및 DB 정보를 설정합니다.

### `application.yml` 예시
```yaml
server:
  port: 8081      # 일반 서버 관리 포트

mcp:
  port: 7070      # MCP 통신용 전용 포트 (외부 연결 시 사용)

db:
  driver: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/mydb
  user: myuser
  pw: mypassword
  schema-output-dir: docs/schema  # 스키마 추출 시 저장될 경로
```

---

## 🏃 실행 방법

### 1. Maven을 이용한 실행
```bash
# 기본 설정으로 실행 (yml 기반)
mvn exec:java -Dexec.mainClass="com.sqlgen.mcp.McpServer"

# 특정 포트로 즉시 오버라이드하여 실행
mvn exec:java -Dexec.mainClass="com.sqlgen.mcp.McpServer" -Dexec.args="9090"
```

### 2. 빌드된 JAR로 실행
```bash
java -jar target/sql-gen-mcp-1.0-SNAPSHOT.jar
```

---

## 🔗 외부 서버 연결 가이드 (MCP 클라이언트 설정)

### 1. Claude Desktop 연결 (SSE 방식)
Claude Desktop에서 외부 서버에 실행 중인 이 프로젝트를 연동하려면 `claude_desktop_config.json`을 수정합니다.

- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "sql-gen-remote": {
      "url": "http://<외부_서버_IP>:7070/sse"
    }
  }
}
```

### 2. 표준 HTTP RPC 연결 (Stateless)
별도의 SSE 핸드셰이크 없이 단일 HTTP POST 요청으로 도구를 실행할 수 있습니다.

- **Endpoint:** `POST http://<외부_서버_IP>:7070/mcp/rpc`
- **Request Body (Example):**
  ```json
  {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "get_table_list",
      "arguments": {}
    }
  }
  ```

---

## 🧰 제공되는 MCP 도구 (Tools)

| 도구명 | 설명 | 파라미터 |
| :--- | :--- | :--- |
| `get_table_list` | 데이터베이스의 모든 테이블 목록을 반환합니다. | 없음 |
| `search_tables` | 테이블 명이나 코멘트에서 키워드를 검색합니다. | `query` (string) |
| `get_table_schema` | 특정 테이블의 컬럼명, 타입, 제약조건 등을 조회합니다. | `tableName` (string) |
| `read_query` | SELECT 문을 실행하여 데이터를 조회합니다. | `sql` (string) |
| `write_query` | INSERT/UPDATE/DELETE 등의 DML을 실행합니다. | `sql` (string) |

---

## 🔒 보안 및 네트워크 참고사항
- **포트 개방:** 외부 서버에서 실행 시 해당 포트(기본 7070)가 방화벽에서 인바운드 허용되어야 합니다.
- **CORS:** 모든 호스트에 대해 CORS가 기본적으로 허용되어 있습니다.
- **주의:** 현재 인증 로직이 포함되어 있지 않으므로, 공인 IP에 노출 시 방화벽에서 특정 IP만 허용하거나 VPN 환경에서 사용하는 것을 권장합니다.
