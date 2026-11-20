package com.pilot.core.message;

import java.util.List;

/**
 * 模型对话消息，对应 OpenAI 兼容协议的 messages 元素。
 *
 * @param role        system / user / assistant / tool
 * @param content     文本内容；assistant 只发工具调用时可为空
 * @param toolCalls   assistant 消息请求的工具调用
 * @param toolCallId  tool 消息对应的调用 ID
 * @param attachments 需要模型侧二次处理的附件（图片等）；文本附件已内联进 content
 */
public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId,
    List<Attachment> attachments, List<Integer> imagePositions, String reasoningContent) {
    public Message {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        imagePositions = imagePositions == null ? List.of() : List.copyOf(imagePositions);
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        long imageCount = attachments.stream().filter(Attachment::image).count();
        if (!imagePositions.isEmpty() && imagePositions.size() != imageCount) {
            throw new IllegalArgumentException("image positions must match image attachments");
        }
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool message requires toolCallId");
        }
    }

    public Message(String role, String content, List<ToolCall> toolCalls, String toolCallId,
        List<Attachment> attachments, List<Integer> imagePositions) {
        this(role, content, toolCalls, toolCallId, attachments, imagePositions, "");
    }

    public Message(String role, String content, List<ToolCall> toolCalls, String toolCallId,
        List<Attachment> attachments) {
        this(role, content, toolCalls, toolCallId, attachments, List.of(), "");
    }

    public Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of(), List.of(), "");
    }

    public Message(String role, String content) {
        this(role, content, List.of(), null, List.of(), List.of(), "");
    }

    public static Message system(String text) {
        return new Message("system", text);
    }

    public static Message user(String text) {
        return new Message("user", text);
    }

    public static Message user(String text, List<Attachment> attachments) {
        return new Message("user", text, List.of(), null, attachments);
    }

    public static Message user(String text, List<Attachment> attachments, List<Integer> imagePositions) {
        return new Message("user", text, List.of(), null, attachments, imagePositions);
    }

    public static Message assistant(String text, List<ToolCall> toolCalls) {
        return assistant(text, toolCalls, "");
    }

    /** 思考模型在同一次工具调用链里要带回 reasoning_content，新的用户消息之后不再发送。 */
    public static Message assistant(String text, List<ToolCall> toolCalls, String reasoningContent) {
        return new Message("assistant", text, toolCalls, null, List.of(), List.of(), reasoningContent);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, List.of(), toolCallId);
    }

    /** 工具产出图片的消息前缀；压缩与历史修剪据此识别，不把截图当作用户原图长期固定。 */
    public static final String TOOL_IMAGE_MARKER = "[工具图片]";

    /** 运行时以 user 角色注入的状态提醒前缀；不是用户输入。 */
    public static final String SYSTEM_REMINDER_PREFIX = "<system-reminder>";
    /** 运行时会话提示（收尾要求等）以 user 角色注入时的前缀。 */
    public static final String RUNTIME_INJECTION_PREFIX = "[系统";
    /** 模型切换提醒前缀；同样由运行时注入，不算新的一轮用户输入。 */
    public static final String MODEL_SWITCH_MARKER = "[模型切换]";
    /** 运行时行为检查点提醒前缀；以 user 角色临时追加但不算新一轮真实用户输入。 */
    public static final String RUNTIME_CHECKPOINT_MARKER = "[运行时检查点]";
    private static final String RUNTIME_CONTEXT_MARKER = "[Runtime task context]";

    /**
     * 工具产出的图片。OpenAI 兼容协议的 tool 消息只能承载文本，所以图片放在本批 tool 结果之后的
     * 一条 user 消息里；文字部分说明来源，模型据此把图片对应回具体的工具调用。
     */
    public static Message toolImages(String description, List<Attachment> images) {
        String text = TOOL_IMAGE_MARKER + " " + (description == null ? "" : description.strip());
        return new Message("user", text, List.of(), null, images);
    }

    public boolean isToolImages() {
        return "user".equals(role) && content.startsWith(TOOL_IMAGE_MARKER);
    }

    /**
     * 运行时以 user 角色注入的内部消息：状态提醒、工具截图、会话临时提示和模型切换提醒。
     * 它们追加在历史尾部，不能当成用户的新一轮输入。
     */
    public boolean isInternalUserMessage() {
        return "user".equals(role) && isInternalUserText(content);
    }

    /** 运行时注入的 user 文本前缀判断；压缩与上下文固定也用它，不重复维护一份前缀表。 */
    public static boolean isInternalUserText(String text) {
        String trimmed = text == null ? "" : text.stripLeading();
        return trimmed.startsWith(TOOL_IMAGE_MARKER) || trimmed.startsWith(SYSTEM_REMINDER_PREFIX)
            || trimmed.startsWith(RUNTIME_INJECTION_PREFIX) || trimmed.startsWith(MODEL_SWITCH_MARKER)
            || trimmed.startsWith(RUNTIME_CHECKPOINT_MARKER) || trimmed.contains(RUNTIME_CONTEXT_MARKER);
    }

    /**
     * 用户真实发起的一轮输入。思考字段的回传边界按它切分：
     * 尾部追加内部提醒时当前轮不变，直到出现下一条真实用户消息才结束回传。
     */
    public boolean isRealUserTurn() {
        return "user".equals(role) && !isInternalUserMessage();
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 最后一条真实用户消息的下标；没有任何真实用户输入时返回 -1。 */
    public static int lastRealUserTurnIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).isRealUserTurn()) {
                return index;
            }
        }
        return -1;
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
}
