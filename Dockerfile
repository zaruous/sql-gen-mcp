# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy pom.xml and download dependencies to cache them
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build the shaded JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built JAR from the build stage
# Note: maven-shade-plugin produces a single fat JAR
COPY --from=build /app/target/sql-gen-mcp-*.jar app.jar

# Copy example config as default application.yml
COPY src/main/resources/application_example.yml application.yml

# Copy log configuration as default logback.xml
COPY src/main/resources/logback.xml logback.xml

# Copy entrypoint script and make it executable
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh && \
    sed -i 's/\r$//' /app/entrypoint.sh

# Create directory for schema output
RUN mkdir -p docs/schema

# Expose ports
EXPOSE 8081 7070

# Default Environment Variables
ENV DB_DRIVER=org.postgresql.Driver
ENV DB_URL=jdbc:postgresql://host.docker.internal:5432/postgres
ENV DB_USER=postgres
ENV DB_PW=password

# Use entrypoint script to run initialization before starting server
ENTRYPOINT ["/app/entrypoint.sh"]
