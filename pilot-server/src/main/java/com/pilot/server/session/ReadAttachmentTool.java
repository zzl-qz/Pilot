package com.pilot.server.session;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

/**
 * 会话附件的只读读取入口：模型用消息里「已保存到」提示的路径读取本会话上传的附件原文。
 *
 * <p>每次调用先验证当前会话的附件引用，再锚定授权文件所在目录（{@link PathValidator}）。
 * 无关会话与工作区内外的其它路径均拒绝。文本分页、PDF 页码、
 * Office 抽取与图片分支全部委托 {@link ReadFileTool}，不重复实现解析逻辑。
 */
public final class ReadAttachmentTool implements Tool.ParallelReadOnly {
    private final Function<String, Path> resolve;

    public ReadAttachmentTool(Path attachmentDir) {
        this(new PathValidator(attachmentDir)::resolve);
    }

    public ReadAttachmentTool(Function<String, Path> resolve) {
        this.resolve = resolve;
    }

    @Override
    public String name() {
        return "read_attachment";
    }

    @Override
    public String description() {
        return "读取本会话上传或继承的附件（只读）。path 用消息里「已保存到」提示的绝对路径；"
            + "支持文本分页（start_line/line_count）、PDF 页码（pages）、Office 文本抽取与图片。" + "只能读当前会话的附件，其它路径会被拒绝。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ReadFileTool.schema("附件绝对路径，来自消息里的「已保存到」提示", "文本起始行号");
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        try {
            Path file = resolve.apply(args.get("path") instanceof String value ? value : null);
            Map<String, Object> resolved = new HashMap<>(args);
            resolved.put("path", file.toString());
            return new ReadFileTool(new PathValidator(file.getParent())).executeResult(resolved);
        } catch (RuntimeException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }
}
