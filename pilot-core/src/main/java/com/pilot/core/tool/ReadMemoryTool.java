package com.pilot.core.tool;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.pilot.core.memory.MemoryStore;
import com.pilot.core.memory.MemoryType;

/**
 * {@code read_memory}：按 {@code slug} 读取 {@code topics/<slug>.md} 正文，或按关键词/分类检索外置记忆。
 *
 * <p>配合启动时注入的 {@code MEMORY.md} 静态薄索引使用：只在真正需要详情或检索时按需调用，
 * 且更新日期陈旧度的提示仅在 {@code read_memory} 读取结果里动态计算，绝不改写系统提示字节，零前缀缓存损耗。
 */
public final class ReadMemoryTool implements Tool.ParallelReadOnly {
    public static final String NAME = "read_memory";
    static final int STALE_DAYS = 7;

    private final MemoryStore store;
    private final Path workspace;
    private final Clock clock;

    public ReadMemoryTool(MemoryStore store, Path workspace) {
        this(store, workspace, Clock.systemDefaultZone());
    }

    public ReadMemoryTool(MemoryStore store, Path workspace, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("slug", ToolSchemas.string("MEMORY.md 索引条目前方方括号中的记忆标识 slug（按条读取必填其一）"));
        properties.put("query", ToolSchemas.string("按关键词检索已采纳记忆的标题与正文（不传 slug 时使用）"));
        properties.put("type", Map.of("type", "string", "enum", List.of("user", "feedback", "project", "reference"),
            "description", "可选：检索时按闭合分类过滤"));
        properties.put("scope",
            Map.of("type", "string", "enum", List.of(MemoryStore.SCOPE_PROJECT, MemoryStore.SCOPE_USER), "description",
                "可选：project 或 user；留空先查当前项目记忆再查个人全局记忆"));
        return ToolSchemas.object(properties, List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        String slug = ToolSupport.stringArg(args, "slug");
        String scope = ToolSupport.stringArg(args, "scope");
        if (slug != null && !slug.isBlank()) {
            return readSingleEntry(slug.strip(), scope);
        }
        String query = ToolSupport.stringArg(args, "query");
        String rawType = ToolSupport.stringArg(args, "type");
        if ((query == null || query.isBlank()) && (rawType == null || rawType.isBlank())) {
            return "ERROR: 请提供 slug（读取单条详情）或 query / type（检索记忆列表）";
        }
        MemoryType type;
        try {
            type = rawType == null || rawType.isBlank() ? null : MemoryType.parse(rawType);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
        return searchEntries(scope, query, type);
    }

    private String readSingleEntry(String slug, String scope) {
        Optional<MemoryStore.Entry> found;
        try {
            found = store.readEntry(workspace, scope, slug);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (found.isEmpty()) {
            return "ERROR: 未找到记忆条目 " + slug;
        }
        MemoryStore.Entry entry = found.get();
        String staleNote = staleCaveat(entry.updated());
        return "[" + entry.slug() + "] scope=" + entry.scope() + " type=" + entry.type()
            + (entry.updated().isEmpty() ? "" : " updated=" + entry.updated()) + "\n标题：" + entry.title() + staleNote
            + "\n\n" + entry.body();
    }

    private String searchEntries(String scope, String query, MemoryType type) {
        List<MemoryStore.Entry> hits;
        try {
            hits = store.searchEntries(workspace, scope, query, type, 20);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (hits.isEmpty()) {
            return "未找到匹配的记忆条目。";
        }
        StringBuilder out = new StringBuilder();
        for (MemoryStore.Entry entry : hits) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("- [").append(entry.slug()).append("] (").append(entry.scope()).append('/').append(entry.type());
            if (!entry.updated().isEmpty()) {
                out.append(", ").append(entry.updated());
            }
            out.append(") ").append(entry.title());
            if (!entry.description().isEmpty() && !entry.description().equals(entry.title())) {
                out.append(" — ").append(entry.description());
            }
        }
        return out.toString();
    }

    private String staleCaveat(String updated) {
        if (updated == null || updated.isBlank()) {
            return "";
        }
        try {
            LocalDate updatedDate = LocalDate.parse(updated.strip());
            long ageDays = ChronoUnit.DAYS.between(updatedDate, LocalDate.now(clock));
            if (ageDays >= STALE_DAYS) {
                return "\n提示：该记忆更新于 " + updatedDate + "（距今 " + ageDays + " 天），若涉及代码路径、符号或配置现状，请先查证当前代码再采信。";
            }
        } catch (DateTimeParseException ignored) {
            // 非标准日期字段忽略陈旧度提示，不阻断正常读取
        }
        return "";
    }
}
