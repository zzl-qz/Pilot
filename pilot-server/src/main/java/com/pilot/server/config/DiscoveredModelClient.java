package com.pilot.server.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.pilot.core.loop.Message;
import com.pilot.core.model.AnthropicMessagesModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.NativeToolRejectedException;
import com.pilot.core.model.OpenAiCompatibleModelClient;
import com.pilot.core.model.OpenAiResponsesModelClient;
import com.pilot.core.model.ToolDefinition;

/** 冻结账号/模型配置，但在正常请求边界读取后台验证与熔断状态。 */
final class DiscoveredModelClient implements ModelClient {
    private final ModelSettings.Snapshot target;
    private final ModelRuntimeSettings runtime;
    private final HttpClient http;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final Duration timeout;
    private final boolean promptCaching;
    private final ModelProfile profile;
    private volatile boolean rejected;
    private ModelCapabilityDiscovery.Report rejectedReport;
    private final java.util.Set<String> rejectedProtocols = new java.util.HashSet<>();
    private String fallbackProtocol = "";
    private String appliedPlan;
    private ModelClient applied;

    DiscoveredModelClient(ModelSettings.Snapshot target, ModelRuntimeSettings runtime, HttpClient http,
        tools.jackson.databind.ObjectMapper mapper, Duration timeout, boolean promptCaching) {
        this.target = target;
        this.runtime = runtime;
        this.http = http;
        this.mapper = mapper;
        this.timeout = timeout;
        this.promptCaching = promptCaching;
        this.profile = runtime.profile(target);
    }

    @Override
    public ModelProfile profile() {
        return profile;
    }

    @Override
    public synchronized Set<NativeCapability> nativeCapabilities() {
        ModelCapabilityDiscovery.Report current = runtime.capabilityReport(target);
        if (rejected && current != null && current != rejectedReport && "verified".equals(current.state())) {
            rejected = false;
            rejectedProtocols.clear();
        }
        return rejected ? Set.of() : runtime.nativeCapabilities(target);
    }

    @Override
    public synchronized boolean disableRejectedNativeTools(Throwable failure) {
        if (!(failure instanceof NativeToolRejectedException refusal)) {
            return false;
        }
        String currentProtocol = applied instanceof AnthropicMessagesModelClient ? "anthropic-messages"
            : applied instanceof OpenAiResponsesModelClient ? "openai-responses" : "chat-completions";
        if (refusal.protocolRejected()) {
            if (!rejectedProtocols.add(currentProtocol)) {
                return false;
            }
            fallbackProtocol = List.of("chat-completions", "anthropic-messages", "openai-responses").stream()
                .filter(protocol -> !rejectedProtocols.contains(protocol)).findFirst().orElse("");
            if (fallbackProtocol.isEmpty()) {
                return false;
            }
        } else {
            if (rejected) {
                return false;
            }
            fallbackProtocol = currentProtocol;
        }
        rejected = true;
        runtime.rejectNative(target, fallbackProtocol, refusal.protocolRejected());
        rejectedReport = runtime.capabilityReport(target);
        return true;
    }

    @Override
    public boolean disableRejectedCache(Throwable failure) {
        return client().disableRejectedCache(failure);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return client().chat(messages, tools);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text,
        Consumer<String> reasoning) {
        return client().chat(messages, tools, text, reasoning);
    }

    private synchronized ModelClient client() {
        nativeCapabilities();
        ModelCapabilityDiscovery.Report report = runtime.capabilityReport(target);
        Set<NativeCapability> capabilities =
            rejected ? Set.of() : report == null ? runtime.nativeCapabilities(target) : report.capabilities();
        String protocol = rejected ? fallbackProtocol : report != null ? report.protocol() : "";
        if (protocol.isBlank()) {
            String mode = runtime.options(target).webSearchMode();
            if ("openai-responses".equals(mode) || "anthropic-messages".equals(mode)) {
                protocol = mode;
            } else if (URI.create(target.endpoint()).getPath().endsWith("/responses")) {
                protocol = "openai-responses";
            }
        }
        boolean messages =
            "anthropic-messages".equals(protocol) || (protocol.isBlank() && runtime.anthropicMessages(target));
        String plan = protocol + ":" + messages + ":" + capabilities;
        if (applied != null && plan.equals(appliedPlan)) {
            return applied;
        }
        URI endpoint = URI.create(target.endpoint());
        if (messages) {
            endpoint = ModelCapabilityDiscovery.endpoint(target.endpoint(), "anthropic-messages");
            applied = new AnthropicMessagesModelClient(http, mapper, endpoint, target.apiKey(), target.model(), timeout,
                profile, capabilities);
        } else if (capabilities.contains(NativeCapability.WEB_SEARCH) || "openai-responses".equals(protocol)) {
            endpoint = ModelCapabilityDiscovery.endpoint(target.endpoint(), "openai-responses");
            applied = new OpenAiResponsesModelClient(http, mapper, endpoint, target.apiKey(), target.model(), timeout,
                profile, capabilities);
        } else {
            // 原有自定义聊天完整路径必须原样保留；只替换明确的其他协议后缀。
            if (endpoint.getPath().endsWith("/messages") || endpoint.getPath().endsWith("/responses")) {
                endpoint = ModelCapabilityDiscovery.endpoint(target.endpoint(), "chat-completions");
            }
            applied = new OpenAiCompatibleModelClient(http, mapper, endpoint, target.apiKey(), target.model(), timeout)
                .withProfile(profile).withPromptCaching(promptCaching);
        }
        appliedPlan = plan;
        return applied;
    }
}
