package com.pilot.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.memory.MemoryStore;
import com.pilot.core.memory.MemoryType;
import com.pilot.core.message.ToolCall;
import com.pilot.core.workspace.PathValidator;

/**
 * {@code remember}：把值得跨会话记住的条目写入外置候选区（不会修改目标代码仓库）。
 *
 * <p>优先写入 {@link MemoryStore}（位于 Pilot 数据目录），带闭合四分类与 {@code why} 依据；
 * 仅在未装配 {@code MemoryStore} 的纯工作区单测场景下回退写工作区 {@code .pilot/memory-draft.md}。
 * 候选条目不会自动注入提示词，只有用户在「设置 → 记忆」审阅采纳后才进入 {@code MEMORY.md}。
 *
 * <p>仍标记为 {@link WritesFiles} 走写操作权限档位控制（{@code READ_ONLY}/只读规划模式自动剥离，
 * {@code ASK} 需用户批准），但外置模式下 {@link #changedPath(ToolCall)} 返回 {@code null}，
 * 避免标脏工作区代码检查。
 */
public final class RememberTool implements Tool, WritesFiles {

    /** 兼容回退路径：仅无外置 MemoryStore 时使用。 */
    static final String DRAFT_PATH = ".pilot/memory-draft.md";
    private static final String DRAFT_HEADER = """
        # 记忆候选区（agent 沉淀，等待人工审阅）

        这里是 agent 通过 remember 工具追加的候选条目，不会注入任何提示词。
        审阅后把值得长期保留的整理进同目录或工作区的 MEMORY.md（那份才会注入），
        其余直接删除；建议定期清空本文件。
        """;
    private static final int MAX_NOTE_CHARS = MemoryStore.MAX_NOTE_CHARS;

    private final PathValidator paths;
    private final SnapshotSink snapshots;
    private final MemoryStore store;
    private final Path workspace;

    public RememberTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public RememberTool(PathValidator paths, SnapshotSink snapshots) {
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = snapshots == null ? SnapshotSink.none() : snapshots;
        this.store = null;
        this.workspace = null;
    }

    public RememberTool(MemoryStore store, Path workspace) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.paths = null;
        this.snapshots = SnapshotSink.none();
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("note", ToolSchemas.string("要沉淀的一条经验/决策/偏好/外部指针，一行以内；含时间必须用绝对日期 YYYY-MM-DD"));
        properties.put("type", Map.of("type", "string", "enum", List.of("user", "feedback", "project", "reference"),
            "description", "闭合分类：user(用户背景) / feedback(偏好与踩坑，默认) / project(项目决策) / reference(外部系统链接)"));
        properties.put("title", ToolSchemas.string("简短标题（可选，80 字以内）"));
        properties.put("why", ToolSchemas.string("为什么这样做或触发条件（可选，建议填写）"));
        return ToolSchemas.object(properties, List.of("note"));
    }

    /** 仍走写权限档位（READ_ONLY/plan 模式不可写，ASK 需审批）。 */
    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String note = ToolSupport.stringArg(args, "note");
        if (note == null || note.isBlank()) {
            return "ERROR: 缺少参数 note";
        }
        String rawType = ToolSupport.stringArg(args, "type");
        MemoryType type;
        try {
            type = MemoryType.parseOrDefault(rawType, MemoryType.FEEDBACK);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
        String title = ToolSupport.stringArg(args, "title");
        String why = ToolSupport.stringArg(args, "why");

        if (store != null) {
            try {
                MemoryStore.Draft draft = store.addDraft(workspace, type, title, note, why);
                return "已记入外置候选区（" + draft.id() + "，分类 " + draft.type() + "，未改动工作区代码）。用户在「设置 → 记忆」审阅采纳后才会注入后续会话。";
            } catch (RuntimeException e) {
                return "ERROR: " + e.getMessage();
            }
        }
        return writeLegacyDraft(type, note, why);
    }

    @Override
    public String changedPath(ToolCall call) {
        if (store != null) {
            return null;
        }
        try {
            return paths.display(paths.resolve(DRAFT_PATH));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String writeLegacyDraft(MemoryType type, String rawNote, String rawWhy) {
        String note = rawNote.strip();
        if (note.length() > MAX_NOTE_CHARS) {
            return "ERROR: note 超过 " + MAX_NOTE_CHARS + " 字符上限，请提炼成一行";
        }
        Path file;
        try {
            file = paths.resolve(DRAFT_PATH);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        try {
            Files.createDirectories(file.getParent());
            boolean first = !Files.exists(file);
            snapshots.beforeWrite(file);
            if (first) {
                Files.writeString(file, DRAFT_HEADER, StandardCharsets.UTF_8);
            }
            String line = "\n- [" + LocalDate.now() + "] [" + type.wire() + "] " + note.replace("\n", " ");
            if (rawWhy != null && !rawWhy.isBlank()) {
                line += " (Why: " + rawWhy.strip().replace("\n", " ") + ")";
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            snapshots.afterWrite(file);
            return "已记入候选区 " + paths.display(file) + "（用户审阅后整理进 MEMORY.md 才会注入后续会话）。"
                + (first ? " 首次创建，文件头已说明审阅规则。" : "");
        } catch (IOException e) {
            return "ERROR: 写入候选区失败: " + e.getMessage();
        }
    }
}
