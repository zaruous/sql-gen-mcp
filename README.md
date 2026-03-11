# SQL MCP Server (Java & Javalin)

이 프로젝트는 **Model Context Protocol (MCP)**을 구현한 SQL 데이터베이스 인터페이스 서버입니다. 
Claude와 같은 AI 에이전트가 데이터베이스 스키마를 탐색하고, 안전하게 쿼리를 실행하며, 테이블 구조를 분석할 수 있도록 돕습니다.

특히, **VectorDB(RAG)** 기능을 통해 수천 개의 테이블 중 사용자의 질문에 가장 적합한 테이블을 자연어로 찾아내는 지능형 검색 기능을 제공합니다.

---

## 🚀 주요 기능
- **MCP 표준 지원 (SSE & STDIO):** Claude Desktop 및 다양한 MCP 클라이언트와 완벽하게 호환됩니다.
- **VectorDB 기반 RAG (Knowledge Base):** 테이블 정의서(JSON)를 벡터화하여 자연어 질의에 최적화된 테이블을 추천합니다.
- **Stateless HTTP RPC:** 상태 비유지 방식의 표준 HTTP 호출을 통해 도구를 실행할 수 있습니다.
- **멀티 DBMS 지원:** PostgreSQL, Oracle, MSSQL 등 다양한 데이터베이스와 연동 가능합니다.
- **DB 탐색 및 분석:** 테이블 목록 조회, 키워드 검색, 상세 스키마 추출, 실행 계획(Explain) 조회를 지원합니다.
- **Swagger UI:** `http://localhost:7070/swagger`를 통해 모든 API를 시각적으로 테스트할 수 있습니다.

---

## 🧠 VectorDB & RAG (지능형 테이블 검색)

이 서버는 **LangChain4j**를 사용하여 데이터베이스 스키마에 대한 지식 베이스를 구축합니다.

### 작동 원리
1. **스키마 추출:** DB에서 테이블 구조를 추출하여 `docs/schema/tables/*.json` 파일로 저장합니다.
2. **벡터화 (Embedding):** 저장된 JSON 정의서를 읽어 벡터 데이터베이스(In-memory)에 인덱싱합니다.
3. **자연어 검색:** 사용자가 "사용자 권한과 관련된 테이블이 뭐야?"라고 물으면, 관련도가 높은 테이블 정의서를 검색하여 반환합니다.

### 설정 (`application.yml`)
임베딩 모델은 로컬(CPU), Ollama, OpenAI 중 선택할 수 있습니다.
```yaml
ai:
  vector-store:
    provider: local  # local (AllMiniLmL6V2), ollama, openai
    providers:
      ollama:
        model: llama3
        url: http://localhost:11434
      openai:
        api-key: ${OPENAI_API_KEY}
```

---

## 🛠️ 설치 및 빌드

### 필수 요구사항
- **Java 17** 이상
- **Maven 3.8** 이상

### 빌드 방법
```bash
mvn clean package
```

---

## ⚙️ 설정 (Configuration)

`application.yml` 파일을 통해 서버 및 DB 정보를 설정합니다.

```yaml
server:
  port: 7070

db:
  driver: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/mydb
  user: myuser
  pw: mypassword
  schema-output-dir: docs/schema
```

---

## 🏃 실행 방법

### 1. 일반 실행 (SSE 방식)
```bash
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar
```

### 2. Claude Desktop 전용 (STDIO 방식)
```bash
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio
```

---

## 🔗 API 및 Swagger (Functions)

서버 실행 후 `http://localhost:7070/swagger`에서 아래 기능들을 직접 호출해볼 수 있습니다.

### 📋 테이블 및 스키마 관리
| Method | Endpoint | 설명 |
| :--- | :--- | :--- |
| `GET` | `/tables` | 전체 테이블 목록 및 코멘트 조회 |
| `GET` | `/tables/search` | 키워드(`?q=...`) 기반 테이블 명 검색 |
| `GET` | `/tables/{name}/schema` | 특정 테이블의 컬럼, 타입, 제약조건 상세 조회 |
| `POST` | `/db/initializeSchema` | 현재 DB의 모든 스키마를 추출하여 벡터 DB에 반영 |

### 🔍 쿼리 실행 및 분석
| Method | Endpoint | 설명 |
| :--- | :--- | :--- |
| `POST` | `/query/read` | SELECT 쿼리 실행 (Body: SQL string) |
| `POST` | `/query/write` | INSERT/UPDATE/DELETE 실행 |
| `POST` | `/query/explain` | SQL 실행 계획(Explain) 조회 및 성능 분석 |

### 🤖 지능형 검색 (RAG)
| Method | Endpoint | 설명 |
| :--- | :--- | :--- |
| `GET` | `/knowledge/search` | 자연어 질의(`?q=...`)로 연관된 테이블 정의서 검색 |

---

## 🧰 MCP 도구 (Tools for AI)

AI 에이전트(Claude 등)가 내부적으로 사용하는 도구 목록입니다.

- `get_table_list`: DB 구조 파악의 시작점.
- `search_tables`: 특정 키워드가 포함된 테이블 탐색.
- `get_table_schema`: 쿼리 작성을 위한 상세 컬럼 정보 획득.
- `read_query`: 데이터 조회 및 분석.
- `write_query`: 데이터 수정 (주의 필요).
- `explain_query`: 복잡한 쿼리의 성능 최적화 가이드 제공.
- `search_knowledge_base`: **(추천)** 방대한 스키마에서 목적에 맞는 테이블을 자연어로 검색.

---

## 🐳 Docker 사용법

```bash
docker-compose up -d
```
- **Swagger:** `http://localhost:7070/swagger`
- **PostgreSQL:** `localhost:5432`

---


``` stdio 모드 샘플 및 초기 데이터 처리
 java -jar .\sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio `
>>   --db.driver=org.postgresql.Driver `
>>   --db.url=jdbc:postgresql://localhost:5433/mesdb `
>>   --db.user=tester1 `
>>   --db.pw=tester1
```


``` mcp 설정 예시
 "mcpServers": {
    "sql-gen-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "위치\\sql-gen-mcp-1.0.0-SNAPSHOT.jar",
        "--stdio"
      ]
    }
  },
```



## 🔒 보안 주의사항
- 본 서버는 기본적으로 CORS가 개방되어 있으며 별도의 인증 로직이 없습니다.
- 외부 노출 시 방화벽 설정이나 리버스 프록시(Nginx 등)를 통한 인증 추가를 권장합니다.
