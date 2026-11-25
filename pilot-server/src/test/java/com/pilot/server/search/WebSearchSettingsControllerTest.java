package com.pilot.server.search;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpServer;

/** 联网搜索设置与实时测试接口契约：掩码、保存、清除、校验、只读测试不落盘。 */
@SpringBootTest(properties = {"pilot.store=memory", "pilot.web-search.local=false"})
@AutoConfigureMockMvc
@com.pilot.server.WindowsJdkLoopbackSensitive
class WebSearchSettingsControllerTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-web-search-api-test");

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir", DATA::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void readsUpdatesMasksClearsApiKeysAndRejectsBadInputs() throws Exception {
        mvc.perform(get("/api/settings/web-search")).andExpect(status().isOk())
            .andExpect(jsonPath("$.provider").value("auto")).andExpect(jsonPath("$.fallbackEnabled").value(true))
            .andExpect(jsonPath("$.providers.length()").value(8));

        // 保存 Bocha 配置与密钥，响应只含掩码不含明文
        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON).content(
            """
                {"provider":"bocha","apiKey":"sk-bocha-super-secret-1234","fallbackEnabled":false,"proxy":"http://127.0.0.1:7890"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("bocha"))
            .andExpect(jsonPath("$.apiKeySet").value(true)).andExpect(jsonPath("$.fallbackEnabled").value(false))
            .andExpect(jsonPath("$.proxy").value("http://127.0.0.1:7890"))
            .andExpect(content().string(not(containsString("sk-bocha-super-secret-1234"))));

        // 留空 apiKey 保持原值
        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON).content("""
            {"provider":"bocha","fallbackEnabled":true,"proxy":""}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.apiKeySet").value(true))
            .andExpect(jsonPath("$.fallbackEnabled").value(true)).andExpect(jsonPath("$.proxy").value(""));

        // 显式清除密钥
        mvc.perform(delete("/api/settings/web-search/api-key").param("provider", "bocha")).andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKeySet").value(false));

        // 非法提供商、非法代理与带内嵌账号密码的代理均返回 400
        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON)
            .content("{\"provider\":\"not-a-provider\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON)
            .content("{\"proxy\":\"ftp://127.0.0.1:7890\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON)
            .content("{\"proxy\":\"http://user:pass@127.0.0.1:7890\"}")).andExpect(status().isBadRequest());

        // 分提供商隔离自定义 apiEndpoint，且 socks5 代理返回明确提示，preferPilotSearch 正常持久化
        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON).content(
            """
                {"provider":"bocha","apiEndpoint":"https://proxy.corp/bocha","proxy":"socks5://127.0.0.1:1080","preferPilotSearch":true}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.apiEndpoint").value("https://proxy.corp/bocha"))
            .andExpect(jsonPath("$.preferPilotSearch").value(true))
            .andExpect(jsonPath("$.proxyNotice", containsString("SOCKS5")));

        mvc.perform(put("/api/settings/web-search").contentType(MediaType.APPLICATION_JSON).content("""
            {"provider":"tavily","proxy":""}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("tavily"))
            .andExpect(jsonPath("$.apiEndpoint").value("")).andExpect(jsonPath("$.proxyNotice").value(""));
    }

    @Test
    void testEndpointExecutesReadOnlySearchWithUnsavedOverrides() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body =
                """
                    {"results":[{"title":"Live Test Hit","url":"https://pilot.example/hit","content":"Verified content","engines":["bing"]}]}
                    """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String mockEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            mvc.perform(post("/api/settings/web-search/test").contentType(MediaType.APPLICATION_JSON).content("""
                {"query":"pilot","provider":"searxng","searxngEndpoint":"%s","fallbackEnabled":false}
                """.formatted(mockEndpoint))).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.sourceId").value("searxng"))
                .andExpect(jsonPath("$.hits[0].title").value("Live Test Hit"))
                .andExpect(jsonPath("$.hits[0].url").value("https://pilot.example/hit"));
        } finally {
            server.stop(0);
        }
    }
}
