# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build fat JAR
mvn clean package

# Run tests
mvn test

# Run single test class
mvn test -Dtest=VectorStoreServiceTest

# Run HTTP server (port 7070)
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar

# Run with custom port
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar 8080

# Run STDIO mode (Claude Desktop integration)
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio

# Run STDIO mode with DB args
java -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar --stdio \
  --db.driver=org.postgresql.Driver \
  --db.url=jdbc:postgresql://localhost:5432/mydb \
  --db.user=user \
  --db.pw=password

# Docker
docker-compose up -d
```

## Architecture

This is a **Model Context Protocol (MCP) server** that bridges Claude AI to databases (PostgreSQL, Oracle, MSSQL). It exposes MCP tools that let Claude query database schemas and execute SQL safely.

### Two transport modes

- **HTTP mode** (default): Javalin REST API + SSE endpoint for MCP connections. Swagger UI at `http://localhost:7070/swagger`.
- **STDIO mode** (`--stdio` flag): Reads/writes JSON-RPC on stdin/stdout for Claude Desktop.

### Request flow

```
Claude → MCP protocol → McpHandler (tool registration) → McpService / VectorStoreService → JdbcTemplate → DB
```

For HTTP mode, an SSE transport layer (`JavalinSseTransport`) bridges Javalin HTTP to the MCP SDK.

### Key source files

| File | Role |
|------|------|
| `McpServer.java` | Entry point. Parses args, bootstraps Spring context, starts Javalin, registers routes, selects HTTP vs STDIO mode. |
| `McpHandler.java` | Registers 7 MCP tools with the MCP SDK. Routes tool calls to services. |
| `McpService.java` | Executes DB queries. Auto-detects DB type from JDBC metadata. Enforces 100-row limit on SELECT queries. |
| `SchemaService.java` | Extracts DB metadata and writes to `docs/schema/tables/*.json` and `docs/schema/*.md`. |
| `SchemaInitService.java` | Orchestrates schema extraction → reload of vector store. |
| `VectorStoreService.java` | RAG engine: embeds table definitions and searches by semantic similarity. |
| `McpController.java` | Registers Javalin routes and SSE endpoint. |
| `DatabaseConfig.java` | Creates `DataSource` and `JdbcTemplate` beans. DB credentials come from env vars first, then `application.yml`. |

### MCP tools registered

1. `get_table_list` – all tables + comments
2. `search_tables` – keyword match on table names
3. `get_table_schema` – column details for a table (reads from JSON files if extracted, falls back to live DB)
4. `read_query` – execute SELECT (auto-limited to 100 rows)
5. `write_query` – execute INSERT/UPDATE/DELETE (registered in MCP but hidden from REST via `@OpenApiIgnore`)
6. `explain_query` – execution plan (EXPLAIN / EXPLAIN PLAN / SET STATISTICS)
7. `search_knowledge_base` – **RAG**: natural language → top-K matching table definitions

### RAG / Vector store

`VectorStoreService` loads JSON table definitions from `docs/schema/tables/`, embeds them using an `EmbeddingModel`, and stores in an in-memory `EmbeddingStore`. On search, the user query is embedded and compared via cosine similarity.

Embedding provider is set in `application.yml` under `ai.vector-store.provider`:
- `local` (default) – AllMiniLmL6V2 running in-process, no external server needed
- `ollama` – requires a running Ollama instance
- `vllm` – requires a vLLM API server

### DB-specific SQL

Each supported DBMS has its own SQL files in `src/main/resources/sql/schema/`:
- `{dbms}_tables.sql` – schema extraction
- `{dbms}_columns.sql` – column details
- `{dbms}_tables_list.sql` – table listing
- `{dbms}_tables_search.sql` – search queries

`McpService` detects the DB type via `DatabaseMetaData.getDatabaseProductName()` and loads the appropriate file.

### Configuration priority

DB credentials: **env vars** (`DB_DRIVER`, `DB_URL`, `DB_USER`, `DB_PW`) override `application.yml` values.

Schema output directory: `db.schema-output-dir` in `application.yml` (default: `docs/schema`).

### Spring without Spring Boot

The project uses Spring JDBC (`JdbcTemplate`) but **not Spring Boot** — the `ApplicationContext` is constructed manually in `McpServer.java` using `AnnotationConfigApplicationContext`. Config is loaded from `application.yml` via Jackson YAML and flattened into Spring `Environment` properties.
