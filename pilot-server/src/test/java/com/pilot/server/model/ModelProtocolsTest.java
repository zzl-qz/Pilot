package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 协议名已经存进用户设置和能力缓存，值不能变；各协议对应的接口路径也固定。 */
class ModelProtocolsTest {

    @Test
    void namesStayStable() {
        assertEquals("anthropic-messages", ModelProtocols.ANTHROPIC_MESSAGES);
        assertEquals("openai-responses", ModelProtocols.OPENAI_RESPONSES);
        assertEquals("chat-completions", ModelProtocols.CHAT_COMPLETIONS);
        assertEquals("auto", ModelProtocols.AUTO);
        assertEquals("local", ModelProtocols.LOCAL);
        assertEquals("models", ModelProtocols.MODELS);
    }

    @Test
    void eachProtocolMapsToItsEndpointPath() {
        String base = "https://llm.example.com/v1/chat/completions";
        assertEquals("https://llm.example.com/v1/messages",
            ModelCapabilityDiscovery.endpoint(base, ModelProtocols.ANTHROPIC_MESSAGES).toString());
        assertEquals("https://llm.example.com/v1/responses",
            ModelCapabilityDiscovery.endpoint(base, ModelProtocols.OPENAI_RESPONSES).toString());
        assertEquals("https://llm.example.com/v1/chat/completions",
            ModelCapabilityDiscovery.endpoint(base, ModelProtocols.CHAT_COMPLETIONS).toString());
        assertEquals("https://llm.example.com/v1/models",
            ModelCapabilityDiscovery.endpoint(base, ModelProtocols.MODELS).toString());
    }
}
