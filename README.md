# SQL MCP Server (Java / Javalin)

**Model Context Protocol (MCP)** 기반의 SQL 데이터베이스 인터페이스 서버입니다.
eXbuilder6 AI Studio 등 MCP 클라이언트가 데이터베이스 스키마를 탐색하고, 쿼리를 실행하며, 테이블 구조를 분석할 수 있도록 돕습니다.

**VectorDB(RAG)** 기능을 통해 수천 개의 테이블 중 자연어 질의에 가장 적합한 테이블을 찾아주는 지능형 검색을 제공합니다.

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| **Streamable HTTP Transport** | MCP 2025-03-26 신규 프로토콜. `POST /mcp` 단일 엔드포인트로 동기 응답 |
| **SSE Transport** | MCP 2024-11-05 구 프로토콜. `GET /sse` + `POST /messages` |
| **STDIO Transport** | Claude Desktop 등 로컬 MCP 클라이언트 연동 |
| **VectorDB RAG** | 테이블 정의서 JSON을 임베딩하여 자연어로 최적 테이블 추천 |
| **멀티 DBMS** | PostgreSQL · Oracle · MSSQL 지원 |
| **Swagger UI** | `http://localhost:7070/swagger` |

---

## MCP 트랜스포트 방식 선택

### Streamable HTTP (권장 — MCP 2025-03-26)

LangChain4j 1.x 이상(`StreamableHttpMcpTransport`)과 호환되는 최신 방식입니다.

```
POST http://localhost:7070/mcp
```

**LangChain4j 클라이언트 설정 예시 (Java)**
```java
StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
        .url("http://localhost:7070/mcp")
        .logRequests(true)
        .logResponses(true)
        .build();
```

**application.yml 연동 예시 (exbuilder6-ai-server)**
```yaml
ai:
  mcp:
    servers:
      - name: "dbhelper"
        url: "http://localhost:7070/mcp"
```

---

### SSE (구 방식 — MCP 2024-11-05)

```
GET  http://localhost:7070/sse          ← 연결
POST http://localhost:7070/messages     ← 메시지 전송 (?sessionId=xxx)
```

---

### STDIO (Claude Desktop 연동)

```bash
java -jar sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio
```

**Claude Desktop `claude_desktop_config.json` 설정 예시**

```json
{
  "mcpServers": {
    "sql-gen-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:/path/to/sql-gen-mcp-1.0.0-SNAPSHOT.jar",
        "--stdio"
      ]
    }
  }
}
```

---

## MCP 도구 목록 (AI Tools)

| 도구명 | 설명 | 입력 파라미터 |
|---|---|---|
| `search_knowledge_base` | **(최우선 권장)** 자연어로 테이블 정의서·스키마 지식 베이스 검색 | `query: string` |
| `search_tables` | 키워드로 테이블명 검색 | `query: string` |
| `get_table_schema` | 특정 테이블의 컬럼·타입·제약조건 상세 조회 | `tableName: string` |
| `read_query` | SELECT SQL 실행 및 결과 반환 | `sql: string` |
| `write_query` | INSERT / UPDATE / DELETE SQL 실행 | `sql: string` |
| `explain_query` | SQL 실행 계획(Execution Plan) 분석 | `sql: string` |
| `get_table_list` | DB 전체 테이블 목록 조회 (테이블 수가 많을 경우 비권장) | 없음 |

> AI가 SQL을 생성할 때 권장 순서: `search_knowledge_base` → `search_tables` → `get_table_schema` → `read_query`

---

## REST API (Swagger)

`http://localhost:7070/swagger` 에서 직접 테스트 가능합니다.

### 테이블 & 스키마

| Method | Endpoint | 설명 |
|---|---|---|
| `GET` | `/tables` | 전체 테이블 목록 및 코멘트 조회 |
| `GET` | `/tables/search?q=키워드` | 키워드 기반 테이블명 검색 |
| `GET` | `/tables/{name}/schema` | 특정 테이블 컬럼·제약조건 상세 |

### 쿼리 실행

| Method | Endpoint | 설명 |
|---|---|---|
| `POST` | `/query/read` | SELECT 쿼리 실행 (Body: SQL 문자열) |
| `POST` | `/query/explain` | SQL 실행 계획 조회 |

### 지식 베이스 (RAG)

| Method | Endpoint | 설명 |
|---|---|---|
| `GET` | `/knowledge/search?q=질문` | 자연어로 연관 테이블 정의서 검색 |
| `POST` | `/schema/extract` | DB 스키마 추출 → JSON 파일 저장 |
| `POST` | `/db/initializeSchema` | 스키마 추출 + 벡터 DB 인덱싱 |

### MCP (직접 호출)

| Method | Endpoint | 설명 |
|---|---|---|
| `POST` | `/mcp` | Streamable HTTP MCP 엔드포인트 |
| `GET` | `/sse` | SSE 방식 MCP 연결 |
| `POST` | `/messages` | SSE 방식 MCP 메시지 전송 |

---

## 설정 (application.yml)

```yaml
server:
  port: 8081       # REST API (내부용)

mcp:
  port: 7070       # MCP 서버 포트 (외부 클라이언트 연결)

# VectorDB & RAG 임베딩 설정
ai:
  vector-store:
    provider: local   # local | ollama | vllm | gemini

    providers:
      ollama:
        base-url: "http://localhost:11434"
        model-name: "nomic-embed-text"
      vllm:
        base-url: "http://localhost:8000/v1"
        model-name: "your-vllm-model"
        api-key: "optional"
      gemini:
        api-key: "your-gemini-api-key"
        model-name: "text-embedding-004"

# 데이터베이스 연결
db:
  driver: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/mydb
  user: myuser
  pw: mypassword
  schema-output-dir: docs/schema   # 스키마 JSON 저장 경로
```

### 지원 DBMS 드라이버 예시

```yaml
# PostgreSQL
db:
  driver: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/mydb

# MSSQL
db:
  driver: com.microsoft.sqlserver.jdbc.SQLServerDriver
  url: jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=false

# Oracle
db:
  driver: oracle.jdbc.OracleDriver
  url: jdbc:oracle:thin:@localhost:1521:xe
```

---

## VectorDB RAG 동작 원리

```
DB 연결
  └─► POST /db/initializeSchema
        ├─ 테이블 스키마 추출
        ├─ docs/schema/tables/*.json 저장
        └─ 벡터 DB 인덱싱 (In-memory)
              └─► GET /knowledge/search?q=자연어 질문
                    └─ 유사도 검색 → 관련 테이블 정의서 반환
```

임베딩 제공자별 특성:

| Provider | 특징 |
|---|---|
| `local` | 외부 서버 불필요. AllMiniLmL6V2 내장 모델 사용. 빠른 시작 |
| `ollama` | 로컬 Ollama 서버 필요. `nomic-embed-text` 권장 |
| `vllm` | OpenAI 호환 API를 제공하는 vLLM 서버 사용 |
| `gemini` | Google Gemini `text-embedding-004` 사용. API 키 필요 |

---

## 빌드 및 실행

### 요구사항
- Java 21
- Maven 3.8+

### 빌드
```bash
mvn clean package
```

### 실행 — HTTP 모드 (기본)
```bash
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar
```
> MCP 서버: `http://localhost:7070`
> Swagger: `http://localhost:7070/swagger`

### 실행 — HTTP 포트 지정
```bash
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar 8080
```

### 실행 — STDIO 모드
```bash
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio
```

### 실행 — STDIO + DB 인자 직접 지정
```bash
java -jar sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio \
  --db.driver=org.postgresql.Driver \
  --db.url=jdbc:postgresql://localhost:5433/mesdb \
  --db.user=tester1 \
  --db.pw=tester1
```

---

## 초기 설정 순서

1. `application.yml` DB 정보 입력
2. 서버 실행
3. `POST /db/initializeSchema` 호출 → 스키마 추출 + 벡터 인덱싱
4. `GET /knowledge/search?q=테이블 설명` 으로 검색 확인
5. MCP 클라이언트(AI Studio 등) 연결

---

## 보안 주의사항

- 기본적으로 CORS가 전체 허용(`anyHost`)되어 있습니다.
- 인증 로직이 없으므로 외부 노출 시 Nginx 등 리버스 프록시에서 인증을 추가하세요.
- `write_query` 도구는 데이터 변경이 가능하므로, 운영 환경에서는 접근 제어에 주의하세요.
