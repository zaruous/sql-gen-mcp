@echo off
setlocal enabledelayedexpansion

:: 1. 변수 설정
set "IMAGE_NAME=callakrsos/sql-gen-mcp"
set "LATEST_TAG=%IMAGE_NAME%:latest"

echo [INFO] Building Image as latest...

:: 2. 빌드 (latest 태그로 바로 빌드)
docker build -t %LATEST_TAG% .

:: 3. 기존 컨테이너 정리 (중복 방지)
echo [INFO] Cleaning up old container...
docker stop mcp-server >nul 2>&1
docker rm mcp-server >nul 2>&1

:: 4. 실행 (DB 설정 포함)
echo [INFO] Running new container...
docker run -d --name mcp-server ^
  -p 7070:7070 ^
  -e DB_URL="jdbc:postgresql://192.168.45.7:5433/dbmes" ^
  -e DB_USER="tester1" ^
  -e DB_PW="tester1" ^
  %LATEST_TAG%

:: 5. Docker Hub 푸시
echo [INFO] Pushing latest tag to Docker Hub...
docker push %LATEST_TAG%

echo --------------------------------------
echo [SUCCESS] Pushed %LATEST_TAG%
echo --------------------------------------
pause