package com.pilot.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.pilot.core.workspace.PathValidator;

/**
 * {@code remember}：把值得跨会话记住的条目追加到候选区 {@code .pilot/memory-draft.md}。
 *
 * <p>克制版的记忆沉淀：工具只写候选区（一行一条、带日期），真正注入提示词的是用户
 * 审阅后整理的 {@code MEMORY.md}——模型不能把任意内容直接写进自己的记忆，污染与
 * 幻觉固化在源头就被挡住。候选区不注入提示词，攒多少条都不占上下文。
 *
 * <p>按写文件对待（追加真实文件），走当前权限档位的审批；写入前过
 * {@link SnapshotSink}，回退/分支语义与普通文件一致。
 */
public final class RememberTool implements Tool, WritesFiles {

    /** 候选区的固定位置：工作区内的 .pilot/memory-draft.md。 */
    static final String DRAFT_PATH = ".pilot/memory-draft.md";
    private static final String DRAFT_HEADER = """
        # 记忆候选区（agent 沉淀，等待人工审阅）

        这里是 agent 通过 remember 工具追加的候选条目，不会注入任何提示词。
        审阅后把值得长期保留的整理进同目录或工作区的 MEMORY.md（那份才会注入），
        其余直接删除；建议定期清空本文件。
        """;
    private static final int MAX_NOTE_CHARS = 500;

    private final PathValidator paths;
    private final SnapshotSink snapshots;

    public RememberTool(PathValidator paths) {
        this(paths, SnapshotSink.none());
    }

    public RememberTool(PathValidator paths, SnapshotSink snapshots) {
        this.paths = java.util.Objects.requireNonNull(paths, "paths must not be null");
        this.snapshots = snapshots == null ? SnapshotSink.none() : snapshots;
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("note", Map.of("type", "string", "description", "要沉淀的一条经验/惯例/踩坑，一行以内；写成用户日后能直接执行的口令式条目")),
            "required", List.of("note"), "additionalProperties", false);
    }

    /** 写真实文件（候选区），按写操作走审批。 */
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
        note = note.strip();
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
            Files.writeString(file, "\n- [" + LocalDate.now() + "] " + note.replace("\n", " ") + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            snapshots.afterWrite(file);
            return "已记入候选区 " + paths.display(file) + "（用户审阅后整理进 MEMORY.md 才会注入后续会话）。"
                + (first ? " 首次创建，文件头已说明审阅规则。" : "");
        } catch (IOException e) {
            return "ERROR: 写入候选区失败: " + e.getMessage();
        }
    }

    @Override
    public String changedPath(ToolCall call) {
        try {
            return paths.display(paths.resolve(DRAFT_PATH));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
