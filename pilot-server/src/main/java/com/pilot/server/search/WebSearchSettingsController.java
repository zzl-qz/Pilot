package com.pilot.server.search;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.tool.WebSearchTool;
import com.pilot.core.tool.search.WebSearchConfig;
import com.pilot.core.tool.search.WebSearchModels.SearchHit;
import com.pilot.server.model.ModelRuntimeSettings;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 联网搜索设置与实时测试接口。
 *
 * <p>API Key 只写不读，任何响应都不回传明文密钥。
 */
@RestController
@RequestMapping("/api/settings/web-search")
@RequiredArgsConstructor
public class WebSearchSettingsController {
    private final WebSearchSettings settings;
    private final ObjectMapper mapper;
    private final ObjectProvider<LocalSearchRuntime> runtimeProvider;
    private final ObjectProvider<ModelRuntimeSettings> modelRuntimeProvider;

    @GetMapping
    public WebSearchSettings.View get() {
        return currentView();
    }

    @PutMapping
    public WebSearchSettings.View update(@RequestBody(required = false) UpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        try {
            settings.update(request.toCommand());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return currentView();
    }

    @DeleteMapping("/api-key")
    public WebSearchSettings.View clearApiKey(@RequestParam(required = false) String provider) {
        try {
            settings.clearApiKey(provider);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return currentView();
    }

    /** 只读实时搜索测试：支持叠加当前表单中尚未保存的字段；不落盘、不回传密钥，始终绕过短时冷却。 */
    @PostMapping("/test")
    public TestResponse test(@RequestBody(required = false) TestRequest request) {
        String query = request == null || request.query() == null || request.query().isBlank() ? "Spring Boot 3"
            : request.query().strip();
        WebSearchConfig config;
        HttpClient tempClient = null;
        try {
            WebSearchSettings.UpdateCommand overrides = request == null ? null : request.toOverrides();
            config = settings.probeConfig(overrides, WebSearchConfig.PublicSources.DEFAULT);
            if (request != null && request.proxy() != null) {
                tempClient = settings.createTemporaryClientIfNeeded(request.proxy());
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            HttpClient client = tempClient != null ? tempClient : settings.httpClient();
            WebSearchTool tool = new WebSearchTool(client, mapper, config);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("query", query);
            args.put("count", request != null && request.count() != null ? request.count() : 5);
            if (request != null && request.category() != null && !request.category().isBlank()) {
                args.put("category", request.category().strip());
            }
            WebSearchTool.SearchReport report = tool.executeDetailed(args, true);
            return new TestResponse(report.ok(), report.sourceId(), report.sourceLabel(), report.triedSources(),
                report.hits(), report.answers(), report.warnings(), report.error(), report.elapsedMs());
        } finally {
            if (tempClient != null) {
                tempClient.close();
            }
        }
    }

    /** 同步托管 SearXNG 配置并尝试重启本机 SearXNG 进程。 */
    @PostMapping("/restart-local")
    public WebSearchSettings.View restartLocal() {
        LocalSearchRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前未加载本机搜索运行时");
        }
        LocalSearchRuntime.RuntimeStatus status = runtime.restart();
        return settings.view(status, nativeSearchState());
    }

    private WebSearchSettings.View currentView() {
        LocalSearchRuntime runtime = runtimeProvider.getIfAvailable();
        LocalSearchRuntime.RuntimeStatus status = runtime == null ? null : runtime.status();
        return settings.view(status, nativeSearchState());
    }

    private WebSearchSettings.NativeSearchState nativeSearchState() {
        ModelRuntimeSettings modelRuntime = modelRuntimeProvider.getIfAvailable();
        if (modelRuntime == null) {
            return new WebSearchSettings.NativeSearchState(false, "");
        }
        try {
            ModelRuntimeSettings.View view = modelRuntime.current();
            boolean nativeWeb = view.nativeCapabilities() != null && view.nativeCapabilities().contains("WEB_SEARCH");
            if (settings.preferPilotSearch()) {
                return new WebSearchSettings.NativeSearchState(false,
                    "已开启「优先使用 Pilot 自配置搜索」：当前模型（" + view.model() + "）将统一调用下方配置的搜索源。");
            }
            if (nativeWeb) {
                return new WebSearchSettings.NativeSearchState(true,
                    "当前模型（" + view.model() + "）默认启用了模型商内置搜索；如需改用下方博查/Tavily/SearXNG，请开启「优先使用 Pilot 自配置搜索」。");
            }
            return new WebSearchSettings.NativeSearchState(false,
                "当前模型（" + view.model() + "）未启用原生搜索，所有联网搜索均走下方 Pilot 配置。");
        } catch (RuntimeException ignored) {
            return new WebSearchSettings.NativeSearchState(false, "");
        }
    }

    public record UpdateRequest(String provider, String apiKey, Boolean clearApiKey, String apiEndpoint,
        String searxngEndpoint, Boolean fallbackEnabled, Boolean preferPilotSearch, String proxy) {
        WebSearchSettings.UpdateCommand toCommand() {
            return new WebSearchSettings.UpdateCommand(provider, apiKey, clearApiKey, apiEndpoint, searxngEndpoint,
                fallbackEnabled, preferPilotSearch, proxy);
        }
    }

    public record TestRequest(String query, Integer count, String category, String provider, String apiKey,
        Boolean clearApiKey, String apiEndpoint, String searxngEndpoint, Boolean fallbackEnabled, String proxy) {
        WebSearchSettings.UpdateCommand toOverrides() {
            return new WebSearchSettings.UpdateCommand(provider, apiKey, clearApiKey, apiEndpoint, searxngEndpoint,
                fallbackEnabled, null, proxy);
        }
    }

    public record TestResponse(boolean ok, String sourceId, String sourceLabel, List<String> triedSources,
        List<SearchHit> hits, List<String> answers, List<String> warnings, String error, long elapsedMs) {
    }
}
