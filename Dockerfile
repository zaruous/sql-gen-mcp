# Stage 1: Build (Maven 빌드)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run (실행 환경 - Alpine 대신 Slim 사용)
FROM eclipse-temurin:21-jre
WORKDIR /app

# [핵심] Debian 계열이므로 libstdc++6 설치가 완벽하게 작동합니다.
RUN apt-get update && apt-get install -y \
    libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

# JAR 파일 복사
COPY --from=build /app/target/sql-gen-mcp-*.jar app.jar

# 설정 및 스크립트 복사
COPY src/main/resources/application_example.yml application.yml
COPY src/main/resources/logback.xml logback.xml
COPY entrypoint.sh /app/entrypoint.sh

# 실행 권한 및 줄바꿈 처리
RUN chmod +x /app/entrypoint.sh && \
    sed -i 's/\r$//' /app/entrypoint.sh

RUN mkdir -p docs/schema data

EXPOSE 8081 7070

# 환경 변수 설정
ENV DB_DRIVER=org.postgresql.Driver
ENV DB_URL=jdbc:postgresql://192.168.45.7:5433/dbmes
ENV DB_USER=tester1
ENV DB_PW=tester1

ENTRYPOINT ["/app/entrypoint.sh"]