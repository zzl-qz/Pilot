package com.pilot.server.model;

/**
 * 模型接口协议的名字。
 * 同时也是设置里「原生网页搜索策略」的取值，已经存进用户设置，不能改。
 */
final class ModelProtocols {
    static final String ANTHROPIC_MESSAGES = "anthropic-messages";
    static final String OPENAI_RESPONSES = "openai-responses";
    static final String CHAT_COMPLETIONS = "chat-completions";
    /** 设置取值：让 Pilot 自己识别。 */
    static final String AUTO = "auto";
    /** 设置取值：只用 Pilot 本地搜索，不碰服务端原生工具。 */
    static final String LOCAL = "local";
    /** 不是对话协议，只在能力探测里用来读模型列表。 */
    static final String MODELS = "models";

    private ModelProtocols() {
    }
}
