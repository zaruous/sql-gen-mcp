package com.sqlgen.mcp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlgen.mcp.model.SqlExample;
import com.sqlgen.mcp.service.SqlExampleService;
import io.javalin.http.Context;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SqlExampleController {

    private final SqlExampleService exampleService;
    private final ObjectMapper objectMapper;

    public SqlExampleController(SqlExampleService exampleService, ObjectMapper objectMapper) {
        this.exampleService = exampleService;
        this.objectMapper = objectMapper;
    }

    // GET /api/examples
    public void list(Context ctx) {
        ctx.json(exampleService.list());
    }

    // POST /api/examples
    public void create(Context ctx) throws Exception {
        Map<?, ?> body = objectMapper.readValue(ctx.body(), Map.class);
        String title       = str(body, "title");
        String description = str(body, "description");
        String sql         = str(body, "sql");
        String category    = str(body, "category");
        List<String> tags  = listOf(body, "tags");

        if (title == null || title.isBlank()) {
            ctx.status(400).json(Map.of("error", "title is required"));
            return;
        }
        if (sql == null || sql.isBlank()) {
            ctx.status(400).json(Map.of("error", "sql is required"));
            return;
        }
        SqlExample created = exampleService.add(title, description, sql, category, tags);
        ctx.status(201).json(created);
    }

    // PUT /api/examples/{id}
    public void update(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        Map<?, ?> body = objectMapper.readValue(ctx.body(), Map.class);
        SqlExample updated = exampleService.update(
            id,
            str(body, "title"),
            str(body, "description"),
            str(body, "sql"),
            str(body, "category"),
            listOf(body, "tags")
        );
        if (updated == null) {
            ctx.status(404).json(Map.of("error", "Example not found: " + id));
            return;
        }
        ctx.json(updated);
    }

    // DELETE /api/examples/{id}
    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        boolean deleted = exampleService.delete(id);
        if (!deleted) {
            ctx.status(404).json(Map.of("error", "Example not found: " + id));
            return;
        }
        ctx.json(Map.of("success", true, "id", id));
    }

    // GET /api/examples/search?q=...&topK=5
    public void search(Context ctx) {
        String q = ctx.queryParamAsClass("q", String.class).getOrDefault("");
        int topK = ctx.queryParamAsClass("topK", Integer.class).getOrDefault(10);
        if (q.isBlank()) {
            ctx.json(Map.of("results", List.of()));
            return;
        }
        List<Map<String, Object>> results = exampleService.search(q, topK);
        ctx.json(Map.of("query", q, "results", results));
    }

    // GET /api/examples/export
    public void export(Context ctx) {
        List<SqlExample> all = exampleService.exportExamples();
        ctx.header("Content-Disposition", "attachment; filename=\"sql-examples.json\"");
        ctx.contentType("application/json");
        ctx.json(all);
    }

    // POST /api/examples/import
    public void importExamples(Context ctx) throws Exception {
        var file = ctx.uploadedFile("file");
        if (file == null) {
            ctx.status(400).json(Map.of("success", false, "error", "No file uploaded"));
            return;
        }
        try (var is = file.content()) {
            List<SqlExample> examples = objectMapper.readValue(
                is, objectMapper.getTypeFactory().constructCollectionType(List.class, SqlExample.class));
            List<SqlExample> imported = exampleService.importExamples(examples);
            ctx.json(Map.of("success", true, "imported", imported.size()));
        } catch (Exception e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                .filter(i -> i instanceof String)
                .map(i -> ((String) i).trim())
                .filter(s -> !s.isEmpty())
                .toList();
        }
        return List.of();
    }
}
