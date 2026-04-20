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
| **VectorDB RAG (하이브리드)** | 텍스트 키워드 매칭 + 벡터 유사도 병합 검색. 한국어·영어 모두 지원 |
| **웹 관리 UI** | 브라우저에서 테이블별 가중치 설정 및 자연어 검색 테스트 (`http://localhost:7070`) |
| **테이블 가중치 관리** | `tool-metadata.json`으로 테이블별 부스트·키워드·추가 설명 설정. MCP 도구 검색에도 반영 |
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
| `search_knowledge_base` | **(최우선 권장)** 자연어로 테이블 정의서·스키마 지식 베이스 검색 | `query: string`, `topK: integer` (선택, 기본값: 15, 최대: 30) |
| `search_tables` | 키워드로 테이블명 검색 | `query: string` |
| `get_table_schema` | 특정 테이블의 컬럼·타입·제약조건 상세 조회 | `tableName: string` |
| `read_query` | SELECT SQL 실행 및 결과 반환 | `sql: string` |
| `explain_query` | SQL 실행 계획(Execution Plan) 분석 | `sql: string` |
| `get_table_list` | DB 전체 테이블 목록 조회 (테이블 수가 많을 경우 비권장) | 없음 |

> AI가 SQL을 생성할 때 권장 순서: `search_knowledge_base` → `search_tables` → `get_table_schema` → `read_query`

> `write_query` (INSERT/UPDATE/DELETE)는 안전을 위해 현재 비활성화되어 있습니다.

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
| `GET` | `/knowledge/search?q=질문&topK=15` | 자연어로 연관 테이블 정의서 검색 (`topK` 선택, 기본값: 15, 최대: 30) |
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
                    └─ 하이브리드 검색 → 관련 테이블 정의서 반환
```

### 하이브리드 검색 알고리즘

`search_knowledge_base` MCP 도구와 `/knowledge/search` REST API 모두 동일한 하이브리드 검색을 사용합니다.

```
점수 체계 (같은 테이블에 여러 점수가 있으면 최대값 사용)
  ├─ 텍스트 키워드 매칭 (한국어·영어 모두 유효)
  │     ├─ 테이블명 정확 일치      : 1.00
  │     ├─ 테이블명 포함           : 0.90
  │     └─ 코멘트/컬럼명/remark 포함: 0.70
  ├─ 벡터 유사도 (영어 의미 커버)
  │     └─ cosine × 0.85 (영어 특화 모델 보정)
  └─ tool-metadata.json 가중치
        └─ score × boost (키워드 매칭 시에만 적용)
```

키워드 풀(테이블명 + 코멘트 + 컬럼명 + remark)을 소문자로 구성하여 한국어 텍스트 매칭을 수행하고, 영어 의미 기반 검색은 벡터 임베딩으로 보완합니다.

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
> 웹 관리 UI: `http://localhost:7070`  
> Swagger: `http://localhost:7070/swagger`

### 실행 — Windows (인코딩 옵션 포함)

`starter.bat`을 사용하면 콘솔 인코딩과 JVM 인코딩을 UTF-8로 설정한 뒤 빌드·실행합니다.

```bat
chcp 65001 > nul
mvn package -DskipTests && java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar
```

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

## Docker

### Docker Compose로 실행 (PostgreSQL 포함)

```bash
docker-compose up -d
```

`docker-compose.yml`에 PostgreSQL 16이 포함되어 있어 별도 DB 없이 바로 실행할 수 있습니다.

### 이미지 직접 빌드 및 실행

```bash
# 빌드
docker build -t sql-gen-mcp .

# 실행 (환경변수로 DB 지정)
docker run -d \
  -p 7070:7070 \
  -p 8081:8081 \
  -e DB_DRIVER=org.postgresql.Driver \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/mydb \
  -e DB_USER=myuser \
  -e DB_PW=mypassword \
  -v $(pwd)/docs/schema:/app/docs/schema \
  sql-gen-mcp
```

### Docker 환경변수

| 환경변수 | 설명 | 기본값 |
|---|---|---|
| `DB_DRIVER` | JDBC 드라이버 클래스명 | `org.postgresql.Driver` |
| `DB_URL` | JDBC 연결 URL | - |
| `DB_USER` | DB 사용자명 | - |
| `DB_PW` | DB 비밀번호 | - |

> `application.yml`을 볼륨 마운트하거나 환경변수로 덮어쓸 수 있습니다. 환경변수가 `application.yml`보다 우선합니다.

### 컨테이너 시작 순서

컨테이너 실행 시 `entrypoint.sh`가 아래 순서로 동작합니다:

```
1. SchemaInitApplication 실행 → DB 스키마 추출 + docs/schema/*.json 저장
2. McpServer 실행 → HTTP/MCP 서버 시작 (포트 7070)
```

스키마 초기화 실패 시 서버가 시작되지 않습니다. DB 연결 정보를 먼저 확인하세요.

### Docker Hub에서 가져오기

```bash
docker pull callakrsos/sql-gen-mcp:latest

docker run -d \
  -p 7070:7070 \
  -e DB_DRIVER=org.postgresql.Driver \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/mydb \
  -e DB_USER=myuser \
  -e DB_PW=mypassword \
  callakrsos/sql-gen-mcp:latest
```

---

## 웹 관리 UI

서버 실행 후 브라우저에서 `http://localhost:7070` 에 접속하면 관리 페이지를 사용할 수 있습니다.

### 테이블 가중치 관리 탭

- 인덱싱된 전체 테이블 목록을 테이블명·DB 코멘트·컬럼 수와 함께 표시
- 각 테이블에 **추가 설명(extraDescription)**, **부스트 가중치(boost)**, **부스트 키워드** 설정 가능
- 설정된 가중치는 웹 UI 자연어 검색과 **MCP 도구(`search_knowledge_base`) 모두에 반영**됨
- 테이블명·설명·추가설명 기준 필터 + 가중치 설정 항목만 보기 옵션 제공

### 자연어 검색 탭

- 키워드 입력 시 하이브리드 검색 결과를 랭킹 순으로 표시
- 각 결과에서 전체 스키마 텍스트 펼치기 가능
- 검색 결과에서 바로 가중치 편집 가능

### 메타데이터 내보내기/가져오기

- 우상단 버튼으로 `tool-metadata.json` 다운로드·업로드 가능
- 팀 간 가중치 설정 공유에 활용

### 관리 REST API

| Method | Endpoint | 설명 |
|---|---|---|
| `GET` | `/api/tools` | 테이블 목록 + 메타데이터 (페이지네이션·필터 지원) |
| `GET` | `/api/tools/search?q=검색어` | 자연어 검색 (하이브리드) |
| `GET` | `/api/tools/status` | 인덱싱 상태 및 테이블 수 |
| `POST` | `/api/tools/{name}/boost` | 테이블 가중치 저장 |
| `DELETE` | `/api/tools/{name}/boost` | 테이블 가중치 삭제 |
| `GET` | `/api/tools/metadata/export` | `tool-metadata.json` 다운로드 |
| `POST` | `/api/tools/metadata/import` | `tool-metadata.json` 업로드 |

---

## tool-metadata.json

`tool-metadata.json` 파일로 테이블별 검색 가중치를 관리합니다. 서버 기동 디렉터리에 위치하며, `application.yml`의 `tool.metadata.path`로 경로를 변경할 수 있습니다.

```json
{
  "USERS": {
    "extraDescription": "사용자 로그인, 권한 관리에 사용",
    "boost": 1.5,
    "keywords": ["사용자", "로그인", "계정"]
  },
  "ORDER_HISTORY": {
    "extraDescription": "",
    "boost": 1.2,
    "keywords": ["주문", "배송"]
  }
}
```

- **extraDescription**: AI가 테이블 선택 시 참고할 추가 설명
- **boost**: 1.0 기준 배수 (1.5 → 점수 ×1.5 상향). 키워드가 쿼리에 매칭될 때에만 적용
- **keywords**: 빈 배열이면 모든 쿼리에 부스트 적용, 값이 있으면 쿼리 포함 시에만 적용

---

## 초기 설정 순서

1. `application.yml` DB 정보 입력 (또는 환경변수 설정)
2. 서버 실행
3. `POST /db/initializeSchema` 호출 → 스키마 추출 + 벡터 인덱싱
4. `http://localhost:7070` 접속 → 테이블 목록 확인 및 가중치 설정
5. `GET /knowledge/search?q=테이블 설명` 으로 검색 확인
6. MCP 클라이언트(AI Studio 등) 연결

---

## 보안 주의사항

- 기본적으로 CORS가 전체 허용(`anyHost`)되어 있습니다.
- 인증 로직이 없으므로 외부 노출 시 Nginx 등 리버스 프록시에서 인증을 추가하세요.
- `write_query` 도구(INSERT/UPDATE/DELETE)는 현재 비활성화 상태입니다. 운영 환경에서 활성화 시 접근 제어에 주의하세요.
