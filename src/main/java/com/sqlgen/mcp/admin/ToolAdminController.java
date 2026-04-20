package com.sqlgen.mcp.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlgen.mcp.service.VectorStoreService;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 웹 관리 페이지용 REST API (Javalin 기반).
 * 테이블 목록 조회/가중치 관리, 지식베이스 자연어 검색, 메타데이터 내보내기/가져오기를 제공합니다.
 */
@Component
public class ToolAdminController {

    private final ToolMetadataStore metadataStore;
    private final VectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper;

    public ToolAdminController(ToolMetadataStore metadataStore,
                               VectorStoreService vectorStoreService,
                               ObjectMapper objectMapper) {
        this.metadataStore = metadataStore;
        this.vectorStoreService = vectorStoreService;
        this.objectMapper = objectMapper;
    }

    // ── GET /api/tools ────────────────────────────────────────────────────────
    // 지식베이스에 인덱싱된 테이블 목록 + 메타데이터 반환
    public void listTools(Context ctx) throws Exception {
        String filter     = Optional.ofNullable(ctx.queryParam("filter")).orElse("");
        boolean boostOnly = Boolean.parseBoolean(Optional.ofNullable(ctx.queryParam("boostOnly")).orElse("false"));
        int page = parseIntOrDefault(ctx.queryParam("page"), 0);
        int size = parseIntOrDefault(ctx.queryParam("size"), 50);

        String lower  = filter.toLowerCase();
        var metaMap   = metadataStore.getAll();
        var summaries = vectorStoreService.getTableSummaries();

        List<Map<String, Object>> items = summaries.stream()
            .map(s -> {
                var meta     = metadataStore.get(s.tableName());
                boolean hasBoost = metaMap.containsKey(s.tableName());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name",             s.tableName());
                item.put("description",      s.comment());
                item.put("columnCount",      s.columnCount());
                item.put("extraDescription", meta.extraDescription());
                item.put("boost",            meta.boost());
                item.put("keywords",         meta.keywords());
                item.put("hasBoost",         hasBoost);
                return item;
            })
            .filter(t -> {
                if (!filter.isBlank()) {
                    boolean match =
                        t.get("name").toString().toLowerCase().contains(lower) ||
                        t.get("description").toString().toLowerCase().contains(lower) ||
                        (t.get("extraDescription") != null &&
                            t.get("extraDescription").toString().toLowerCase().contains(lower));
                    if (!match) return false;
                }
                return !boostOnly || (boolean) t.get("hasBoost");
            })
            .sorted(Comparator.comparing(t -> t.get("name").toString()))
            .toList();

        int total      = items.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
        List<Map<String, Object>> paged = items.stream().skip((long) page * size).limit(size).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",        total);
        result.put("page",         page);
        result.put("size",         size);
        result.put("totalPages",   totalPages);
        result.put("tools",        paged);
        result.put("indexReady",   vectorStoreService.isReady());
        result.put("totalIndexed", vectorStoreService.getTableCount());
        ctx.contentType("application/json").result(objectMapper.writeValueAsString(result));
    }

    // ── GET /api/tools/search?q=... ───────────────────────────────────────────
    public void search(Context ctx) throws Exception {
        String q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.contentType("application/json")
               .result(objectMapper.writeValueAsString(Map.of("error", "검색어를 입력하세요.", "results", List.of())));
            return;
        }
        if (!vectorStoreService.isReady()) {
            ctx.contentType("application/json")
               .result(objectMapper.writeValueAsString(
                   Map.of("error", "인덱싱 진행 중입니다. 잠시 후 다시 시도하세요.", "results", List.of())));
            return;
        }

        List<String> rawResults = vectorStoreService.search(q, 15);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < rawResults.size(); i++) {
            String text      = rawResults.get(i);
            String firstLine = text.lines().findFirst().orElse("").trim();
            String tableName = firstLine.startsWith("Table:") ? firstLine.substring(6).trim() : firstLine;
            String descLine  = text.lines()
                .filter(l -> l.startsWith("Description:"))
                .findFirst()
                .map(l -> l.substring(12).trim())
                .orElse("");

            var meta     = metadataStore.get(tableName);
            boolean hasBoost = metadataStore.getAll().containsKey(tableName);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank",             i + 1);
            item.put("tableName",        tableName);
            item.put("description",      descLine);
            item.put("extraDescription", meta.extraDescription());
            item.put("boost",            meta.boost());
            item.put("keywords",         meta.keywords());
            item.put("hasBoost",         hasBoost);
            item.put("text",             text);
            results.add(item);
        }

        ctx.contentType("application/json")
           .result(objectMapper.writeValueAsString(Map.of("query", q, "results", results)));
    }

    // ── GET /api/tools/status ─────────────────────────────────────────────────
    public void status(Context ctx) throws Exception {
        ctx.contentType("application/json")
           .result(objectMapper.writeValueAsString(Map.of(
               "ready", vectorStoreService.isReady(),
               "total", vectorStoreService.getTableCount()
           )));
    }

    // ── POST /api/tools/{name}/boost ──────────────────────────────────────────
    public void setBoost(Context ctx) throws Exception {
        String name = ctx.pathParam("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);
        String extraDesc      = String.valueOf(body.getOrDefault("extraDescription", ""));
        double boost          = ((Number) body.getOrDefault("boost", 1.0)).doubleValue();
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) body.getOrDefault("keywords", List.of());
        metadataStore.set(name, extraDesc, boost, keywords);
        ctx.contentType("application/json")
           .result(objectMapper.writeValueAsString(Map.of("success", true, "message", "저장 완료.")));
    }

    // ── DELETE /api/tools/{name}/boost ────────────────────────────────────────
    public void removeBoost(Context ctx) throws Exception {
        String name = ctx.pathParam("name");
        metadataStore.remove(name);
        ctx.contentType("application/json")
           .result(objectMapper.writeValueAsString(Map.of("success", true, "message", "삭제 완료.")));
    }

    // ── GET /api/tools/metadata/export ────────────────────────────────────────
    public void exportMetadata(Context ctx) throws Exception {
        byte[] data = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(metadataStore.getAll());
        ctx.header("Content-Disposition", "attachment; filename=\"tool-metadata.json\"")
           .contentType("application/json")
           .result(data);
    }

    // ── POST /api/tools/metadata/import ──────────────────────────────────────
    public void importMetadata(Context ctx) throws Exception {
        UploadedFile uploadedFile = ctx.uploadedFile("file");
        if (uploadedFile == null) {
            ctx.contentType("application/json")
               .result(objectMapper.writeValueAsString(Map.of("success", false, "error", "파일이 없습니다.")));
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> raw =
                objectMapper.readValue(uploadedFile.content().readAllBytes(), Map.class);
            Map<String, ToolMetadataStore.ToolMeta> parsed = new LinkedHashMap<>();
            raw.forEach((tName, meta) -> {
                String desc  = (String) meta.getOrDefault("extraDescription", "");
                double boost = ((Number) meta.getOrDefault("boost", 1.0)).doubleValue();
                @SuppressWarnings("unchecked")
                List<String> kws = (List<String>) meta.getOrDefault("keywords", List.of());
                parsed.put(tName, new ToolMetadataStore.ToolMeta(desc, boost, kws));
            });
            metadataStore.replaceAll(parsed);
            ctx.contentType("application/json")
               .result(objectMapper.writeValueAsString(Map.of("success", true, "imported", parsed.size())));
        } catch (Exception e) {
            ctx.contentType("application/json")
               .result(objectMapper.writeValueAsString(Map.of("success", false, "error", e.getMessage())));
        }
    }

    private int parseIntOrDefault(String val, int def) {
        if (val == null) return def;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return def; }
    }
}
