chcp 65001 > nul
mvn package -DskipTests && java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target/sql-gen-mcp-1.0.0-SNAPSHOT.jar