package com.pilot.server.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;

/**
 * 会话附件的只读读取入口：模型用消息里「已保存到」提示的路径读取本会话上传的附件原文。
 *
 * <p>路径校验锚定当前会话的附件目录（{@link PathValidator}），其它会话与工作区内外的
 * 其它路径一律拒绝——附件因此可用，而工作区边界不被放宽。文本分页、PDF 页码、
 * Office 抽取与图片分支全部委托 {@link ReadFileTool}，不重复实现解析逻辑。
 */
public final class ReadAttachmentTool implements Tool {
    private final ReadFileTool delegate;

    public ReadAttachmentTool(Path attachmentDir) {
        this.delegate = new ReadFileTool(new PathValidator(attachmentDir));
    }

    @Override
    public String name() {
        return "read_attachment";
    }

    @Override
    public String description() {
        return "读取本会话上传的附件（只读）。path 用消息里「已保存到」提示的绝对路径；" + "支持文本分页（start_line/line_count）、PDF 页码（pages）、Office 文本抽取与图片。"
            + "只能读当前会话的附件，其它路径会被拒绝。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("path", Map.of("type", "string", "description", "附件绝对路径，来自消息里的「已保存到」提示"), "start_line",
                Map.of("type", "integer", "description", "文本起始行号。正数从 1 开始，负数从末尾数，-1 是最后一行。默认 1"), "line_count",
                Map.of("type", "integer", "description", "最多读取行数，默认 200，最大 2000"), "pages",
                Map.of("type", "string", "description", "仅 PDF：页码或范围，例如 \"3\" 或 \"2-5\"，默认前 10 页，单次最多 30 页")),
            "required", List.of("path"), "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        return delegate.executeResult(args);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return delegate.execute(args);
    }
}
