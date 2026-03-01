#!/bin/sh

# 1. DB 스키마 초기화 실행 (SchemaInitApplication)
echo "--------------------------------------------------"
echo "Starting Schema Initialization..."
echo "--------------------------------------------------"
java -Dlogback.configurationFile=/app/logback.xml -cp app.jar com.sqlgen.mcp.SchemaInitApplication

# 초기화 실패 시 중단 (선택 사항)
if [ $? -ne 0 ]; then
    echo "Schema initialization failed. Exiting."
    exit 1
fi

# 2. 메인 MCP 서버 실행 (McpServer)
echo "--------------------------------------------------"
echo "Starting SQL MCP Server..."
echo "--------------------------------------------------"
# Docker ENTRYPOINT에서 전달된 인자($@)를 그대로 서버에 전달 (포트 번호 등)
exec java -Dlogback.configurationFile=/app/logback.xml -jar app.jar "$@"
