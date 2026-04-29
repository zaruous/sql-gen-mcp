package com.sqlgen.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SqlExample {
    private String id;
    private String title;
    private String description;
    private String sql;
    private String category;
    private List<String> tags;
    private String createdAt;
    private String updatedAt;

    public SqlExample() {}

    public static SqlExample create(String title, String description, String sql,
                                    String category, List<String> tags) {
        SqlExample ex = new SqlExample();
        ex.id = UUID.randomUUID().toString();
        ex.title = title != null ? title : "";
        ex.description = description != null ? description : "";
        ex.sql = sql != null ? sql : "";
        ex.category = category != null ? category : "";
        ex.tags = tags != null ? tags : List.of();
        String now = Instant.now().toString();
        ex.createdAt = now;
        ex.updatedAt = now;
        return ex;
    }

    public String getId()                     { return id; }
    public void setId(String id)              { this.id = id; }
    public String getTitle()                  { return title; }
    public void setTitle(String title)        { this.title = title; }
    public String getDescription()            { return description; }
    public void setDescription(String d)      { this.description = d; }
    public String getSql()                    { return sql; }
    public void setSql(String sql)            { this.sql = sql; }
    public String getCategory()               { return category; }
    public void setCategory(String category)  { this.category = category; }
    public List<String> getTags()             { return tags; }
    public void setTags(List<String> tags)    { this.tags = tags; }
    public String getCreatedAt()              { return createdAt; }
    public void setCreatedAt(String t)        { this.createdAt = t; }
    public String getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(String t)        { this.updatedAt = t; }
}
