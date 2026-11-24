package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.pilot.core.WindowsJdkLoopbackSensitive;
import com.pilot.core.tool.search.WebSearchConfig;
import com.pilot.core.tool.search.WebSearchProvider;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

@WindowsJdkLoopbackSensitive
class WebToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void searchReadsSearxngJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            assertNotNull(query);
            assertTrue(query.contains("format=json"));
            byte[] body =
                "{\"results\":[{\"title\":\"Example\",\"url\":\"https://example.com\",\"content\":\"A result\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebSearchTool tool = new WebSearchTool(HttpClient.newHttpClient(), mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            String result = tool.execute(Map.of("query", "example"));
            assertTrue(result.contains("https://example.com"));
            assertTrue(result.contains("A result"));
            assertTrue(result.contains("Example"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchSaysWhenLocalEngineIsDown() {
        WebSearchTool tool = new WebSearchTool(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            mapper, URI.create("http://127.0.0.1:1"));
        assertTrue(tool.execute(Map.of("query", "pilot")).contains("本地搜索未启动"));
    }

    @Test
    void searchTimesOutWhileWaitingForResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.createContext("/search", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSearchTool tool = new WebSearchTool(client, mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(500));
            assertTrue(tool.execute(Map.of("query", "pilot")).contains("搜索请求超时"));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void searchSearxngSendsHeadersParamsAndDeduplicatesWithDiagnostics() throws Exception {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        AtomicReference<String> realIp = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getRawQuery());
            forwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            realIp.set(exchange.getRequestHeaders().getFirst("X-Real-IP"));
            String payload = """
                {
                  "answers": ["Spring Boot 3 requires Java 17+"],
                  "infoboxes": [{"infobox": "Spring Boot", "content": "Java web framework", "urls": [{"url": "https://spring.io"}]}],
                  "suggestions": ["spring boot 3 migration"],
                  "unresponsive_engines": [["baidu", "parsing error"], ["duckduckgo", "CAPTCHA"]],
                  "results": [
                    {"title": "Docs", "url": "https://docs.spring.io/boot/?utm_source=test#top", "content": "Short", "publishedDate": "2025-05-01T12:00:00", "engines": ["bing"], "score": 1.0},
                    {"title": "Spring Boot Official Docs", "url": "https://docs.spring.io/boot", "content": "Detailed official documentation for Spring Boot 3", "engines": ["google"], "score": 1.0},
                    {"title": "Subdomain Guide", "url": "https://ref.docs.spring.io/boot/guide", "content": "Spring Boot reference guide", "engines": ["brave"], "score": 0.8},
                    {"title": "Off-Domain Noise", "url": "https://merriam-webster.com/dictionary/spring", "content": "Dictionary entry", "engines": ["bing"], "score": 1.5}
                  ]
                }
                """;
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSearchTool tool = new WebSearchTool(client, mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            WebSearchTool.SearchReport report = tool.executeDetailed(Map.of(
                "query", "spring boot",
                "count", 5,
                "category", "it",
                "time_range", "week",
                "language", "zh",
                "site", "https://docs.spring.io/guide",
                "page", 2));
            assertTrue(report.ok());
            assertEquals("127.0.0.1", forwardedFor.get());
            assertEquals("127.0.0.1", realIp.get());
            assertTrue(capturedQuery.get().contains("categories=general,it"));
            assertTrue(capturedQuery.get().contains("time_range=week"));
            assertTrue(capturedQuery.get().contains("language=zh-CN"));
            assertTrue(capturedQuery.get().contains("pageno=2"));
            assertTrue(capturedQuery.get().contains("site%3Adocs.spring.io"));
            assertEquals(2, report.hits().size());
            assertEquals("Spring Boot Official Docs", report.hits().get(0).title());
            assertEquals(List.of("bing", "google"), report.hits().get(0).engines());
            assertEquals("2025-05-01", report.hits().get(0).publishedDate());
            assertFalse(report.formattedText().contains("merriam-webster.com"));
            assertTrue(report.formattedText().contains("直接回答:"));
            assertTrue(report.formattedText().contains("知识摘要:"));
            assertTrue(report.formattedText().contains("相关搜索建议: spring boot 3 migration"));
            assertTrue(report.formattedText().contains("baidu(parsing error)"));
            assertTrue(report.formattedText().contains("[Spring Boot Official Docs](https://docs.spring.io/boot)"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchSupportsCommercialApiProvidersAndScrubsKeysOnFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bocha", exchange -> {
            assertEquals("Bearer bocha-secret-999999", exchange.getRequestHeaders().getFirst("Authorization"));
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(req.contains("\"freshness\":\"oneWeek\""));
            byte[] body = """
                {"data":{"webPages":{"value":[{"name":"博查结果","url":"https://bocha.example/a","summary":"中文摘要","datePublished":"2026-03-01T00:00:00Z"}]}}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/tavily", exchange -> {
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(req.contains("\"include_domains\":[\"docs.spring.io\"]"));
            assertTrue(req.contains("\"topic\":\"news\""));
            byte[] body = """
                {"answer":"Direct Tavily answer","results":[{"title":"Tavily Hit","url":"https://docs.spring.io/x","content":"Tavily snippet","published_date":"2026-04-02","score":0.95}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/brave", exchange -> {
            assertEquals("BSA-brave-secret-1234", exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            assertTrue(exchange.getRequestURI().getRawQuery().contains("freshness=pd"));
            byte[] body = """
                {"web":{"results":[{"title":"Brave <b>Hit</b>","url":"https://brave.example/1","description":"Brave &amp; clean","page_age":"2026-05-03T10:00:00"}]}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/serper/news", exchange -> {
            assertEquals("serper-secret-8888", exchange.getRequestHeaders().getFirst("X-API-KEY"));
            byte[] body = """
                {"answerBox":{"answer":"Serper direct"},"news":[{"title":"Serper News","link":"https://news.example/n","snippet":"Headline","date":"2 hours ago"}],"relatedSearches":[{"query":"follow up"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/exa", exchange -> {
            assertEquals("exa-secret-7777", exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = """
                {"results":[{"title":"Exa Paper","url":"https://arxiv.org/abs/2501.00001","text":"Semantic abstract","publishedDate":"2026-01-15T00:00:00Z","score":0.9}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/bocha-fail", exchange -> {
            byte[] body = "invalid key bocha-secret-leak-token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (HttpClient client = HttpClient.newHttpClient()) {
            // Bocha
            var bocha = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.BOCHA,
                "bocha-secret-999999", base.resolve("/bocha"), base, Duration.ofSeconds(5), false, Map.of(), null));
            String bochaOut = bocha.execute(Map.of("query", "测试", "time_range", "week"));
            assertTrue(bochaOut.contains("博查结果"));
            assertTrue(bochaOut.contains("2026-03-01"));

            // Tavily
            var tavily = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.TAVILY,
                "tvly-secret-123456", base.resolve("/tavily"), base, Duration.ofSeconds(5), false, Map.of(), null));
            String tavilyOut = tavily.execute(Map.of("query", "spring", "category", "news", "site", "docs.spring.io"));
            assertTrue(tavilyOut.contains("Direct Tavily answer"));
            assertTrue(tavilyOut.contains("Tavily Hit"));

            // Brave
            var brave = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.BRAVE,
                "BSA-brave-secret-1234", base.resolve("/brave"), base, Duration.ofSeconds(5), false, Map.of(), null));
            String braveOut = brave.execute(Map.of("query", "brave", "time_range", "day"));
            assertTrue(braveOut.contains("Brave Hit"));
            assertTrue(braveOut.contains("Brave & clean"));

            // Serper (/search -> /news when category=news)
            var serper = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.SERPER,
                "serper-secret-8888", base.resolve("/serper/search"), base, Duration.ofSeconds(5), false, Map.of(),
                null));
            String serperOut = serper.execute(Map.of("query", "ai", "category", "news"));
            assertTrue(serperOut.contains("Serper direct"));
            assertTrue(serperOut.contains("Serper News"));
            assertTrue(serperOut.contains("follow up"));

            // Exa
            var exa = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.EXA,
                "exa-secret-7777", base.resolve("/exa"), base, Duration.ofSeconds(5), false, Map.of(), null));
            String exaOut = exa.execute(Map.of("query", "transformer", "category", "science"));
            assertTrue(exaOut.contains("Exa Paper"));

            // Error scrubbing: API key must never echo back
            var failing = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.BOCHA,
                "bocha-secret-leak-token", base.resolve("/bocha-fail"), base, Duration.ofSeconds(5), false, Map.of(),
                null));
            String failOut = failing.execute(Map.of("query", "secret"));
            assertTrue(failOut.startsWith("ERROR:"));
            assertFalse(failOut.contains("bocha-secret-leak-token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchFallsBackThroughApiAndSearxngToOfficialPublicApisIncludingGzipAndXml() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bocha", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/search", exchange -> {
            byte[] body = "{\"results\":[],\"unresponsive_engines\":[[\"duckduckgo\",\"CAPTCHA\"]]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/wiki-en", exchange -> {
            byte[] body = """
                {"query":{"search":[{"title":"Java (programming language)","snippet":"<span>Java</span> is a language","timestamp":"2026-02-01T00:00:00Z"}]}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/github", exchange -> {
            byte[] body = """
                {"items":[{"full_name":"spring-projects/spring-boot","html_url":"https://github.com/spring-projects/spring-boot","description":"Spring Boot","language":"Java","stargazers_count":75000,"updated_at":"2026-06-01T00:00:00Z"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/stack", exchange -> {
            byte[] json = """
                {"items":[{"title":"How to fix &amp; configure Java?","link":"https://stackoverflow.com/q/123","answer_count":4,"score":42,"is_answered":true,"tags":["java","spring"],"last_activity_date":1735689600}]}
                """.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(gzOut)) {
                gz.write(json);
            }
            byte[] compressed = gzOut.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });
        server.createContext("/arxiv", exchange -> {
            byte[] xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2601.12345v1</id>
                    <published>2026-01-12T00:00:00Z</published>
                    <title>Attention Revisited</title>
                    <summary>A concise paper abstract.</summary>
                  </entry>
                </feed>
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            exchange.getResponseBody().write(xml);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var publicSources = new WebSearchConfig.PublicSources(base.resolve("/wiki-en"), base.resolve("/wiki-en"),
            base.resolve("/github"), base.resolve("/stack"), base.resolve("/arxiv"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSearchConfig cfg = new WebSearchConfig(WebSearchProvider.AUTO, "", base.resolve("/bocha"), base,
                Duration.ofSeconds(5), true, Map.of(WebSearchProvider.BOCHA, "bocha-k"), publicSources);
            WebSearchTool tool = new WebSearchTool(client, mapper, cfg);

            WebSearchTool.SearchReport itReport = tool.executeDetailed(Map.of("query", "spring boot java",
                "category", "it", "count", 8));
            assertTrue(itReport.ok());
            assertEquals("public", itReport.sourceId());
            assertEquals(List.of("bocha", "searxng", "public"), itReport.triedSources());
            assertTrue(itReport.formattedText().contains("自动回退"));
            assertTrue(itReport.formattedText().contains("spring-projects/spring-boot"));
            assertTrue(itReport.formattedText().contains("How to fix & configure Java?"));

            WebSearchTool.SearchReport scienceReport = tool.executeDetailed(Map.of("query", "attention",
                "category", "science", "count", 5));
            assertTrue(scienceReport.ok());
            assertTrue(scienceReport.formattedText().contains("Attention Revisited"));
            assertTrue(scienceReport.formattedText().contains("arxiv"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchReranksByRelevanceSupplementsPartialSearxngAndRetriesTransient503() throws Exception {
        AtomicInteger bochaAttempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bocha-flaky", exchange -> {
            if (bochaAttempts.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = """
                {"data":{"webPages":{"value":[{"name":"Bocha Recovered","url":"https://bocha.example/ok","summary":"Recovered on retry"}]}}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/search", exchange -> {
            byte[] body = """
                {"results":[
                  {"title":"Unrelated Thymeleaf Template Issue","url":"https://so.example/1","content":"Completely unrelated snippet","engines":["stackoverflow"],"score":1.0}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/github", exchange -> {
            byte[] body = """
                {"items":[{"full_name":"openjdk/httpclient-timeout-demo","html_url":"https://github.com/openjdk/httpclient-timeout-demo","description":"Java HttpClient timeout configuration examples","language":"Java","stargazers_count":500}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/empty", exchange -> {
            byte[] body = "{\"items\":[],\"query\":{\"search\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var publicSources = new WebSearchConfig.PublicSources(base.resolve("/empty"), base.resolve("/empty"),
            base.resolve("/github"), base.resolve("/empty"), base.resolve("/empty"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            // 1. Transient 503 retry succeeds on attempt 2
            var retryTool = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.BOCHA, "k-retry",
                base.resolve("/bocha-flaky"), base, Duration.ofSeconds(5), false, Map.of(), publicSources));
            WebSearchTool.SearchReport retryReport = retryTool.executeDetailed(Map.of("query", "bocha"));
            assertTrue(retryReport.ok());
            assertEquals(2, bochaAttempts.get());
            assertTrue(retryReport.formattedText().contains("Bocha Recovered"));

            // 2. Partial SearXNG result (<3) auto-supplements from PUBLIC and relevance reranks matching hit first
            var partialTool = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.SEARXNG, "", null,
                base, Duration.ofSeconds(5), true, Map.of(), publicSources));
            WebSearchTool.SearchReport report = partialTool.executeDetailed(Map.of(
                "query", "Java HttpClient timeout", "category", "it", "count", 5));
            assertTrue(report.ok());
            assertEquals("searxng+public", report.sourceId());
            assertEquals(2, report.hits().size());
            assertEquals("openjdk/httpclient-timeout-demo", report.hits().get(0).title());
            assertTrue(report.formattedText().contains("自动补齐"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchAutoFailsOverAcrossMultipleApiKeysUsesShortCooldownAndDetectsGithubRateLimitAndArxivTerms()
        throws Exception {
        AtomicInteger bochaCalls = new AtomicInteger();
        AtomicInteger tavilyCalls = new AtomicInteger();
        AtomicReference<String> arxivQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bocha-429", exchange -> {
            bochaCalls.incrementAndGet();
            byte[] body = "rate limited".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/tavily-ok", exchange -> {
            tavilyCalls.incrementAndGet();
            byte[] body = """
                {"results":[{"title":"Tavily Backup Hit","url":"https://tavily.example/ok","content":"Backup success","score":0.9}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/github-403", exchange -> {
            exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
            byte[] body = "{\"message\":\"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/arxiv", exchange -> {
            arxivQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2602.00001v1</id>
                    <published>2026-02-01T00:00:00Z</published>
                    <title>Mixture of Experts Survey</title>
                    <summary>Survey abstract.</summary>
                  </entry>
                </feed>
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            exchange.getResponseBody().write(xml);
            exchange.close();
        });
        server.createContext("/hn", exchange -> {
            byte[] body = """
                {"hits":[{"title":"Show HN: Zero-Key Local Search","url":"https://hn.example/zero-key","objectID":"42","points":312,"num_comments":88,"created_at":"2026-06-10T10:00:00Z"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/openalex", exchange -> {
            byte[] body = """
                {"results":[{"display_name":"OpenAlex Mixture of Experts Paper","primary_location":{"landing_page_url":"https://openalex.example/paper","source":{"display_name":"NeurIPS"}},"cited_by_count":128,"publication_date":"2026-03-15"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/empty", exchange -> {
            byte[] body = "{\"items\":[],\"query\":{\"search\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var publicSources = new WebSearchConfig.PublicSources(base.resolve("/empty"), base.resolve("/empty"),
            base.resolve("/github-403"), base.resolve("/empty"), base.resolve("/arxiv"), base.resolve("/hn"),
            base.resolve("/openalex"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            // 1. AUTO mode tries Bocha (429 -> enters cooldown), fails over to Tavily using isolated endpoint
            WebSearchConfig cfg = new WebSearchConfig(WebSearchProvider.AUTO, "", null, base, Duration.ofSeconds(5),
                true, Map.of(WebSearchProvider.BOCHA, "k-bocha", WebSearchProvider.TAVILY, "k-tavily"), publicSources,
                Map.of(WebSearchProvider.BOCHA, base.resolve("/bocha-429"),
                    WebSearchProvider.TAVILY, base.resolve("/tavily-ok")));
            WebSearchTool tool = new WebSearchTool(client, mapper, cfg);

            WebSearchTool.SearchReport first = tool.executeDetailed(Map.of("query", "backup"));
            assertTrue(first.ok());
            assertEquals("tavily", first.sourceId());
            assertEquals(List.of("bocha", "tavily"), first.triedSources());
            assertEquals(1, bochaCalls.get());
            assertEquals(1, tavilyCalls.get());

            // 2. Second call skips cooling Bocha (no extra network hit)
            WebSearchTool.SearchReport second = tool.executeDetailed(Map.of("query", "backup"));
            assertTrue(second.ok());
            assertEquals("tavily", second.sourceId());
            assertEquals(1, bochaCalls.get(), "冷却期内不应重复打 429 的博查端点");
            assertEquals(2, tavilyCalls.get());
            assertTrue(second.formattedText().contains("冷却中"));

            // 3. bypassCooldown=true (Settings live test) still probes Bocha directly
            WebSearchTool.SearchReport liveProbe = tool.executeDetailed(Map.of("query", "backup"), true);
            assertTrue(liveProbe.ok());
            assertEquals(2, bochaCalls.get());

            // 4. GitHub rate limit emits readable Chinese warning & arXiv builds multi-term AND query
            WebSearchTool pubTool = new WebSearchTool(client, mapper, new WebSearchConfig(WebSearchProvider.PUBLIC, "",
                null, base, Duration.ofSeconds(5), false, Map.of(), publicSources, Map.of()));
            WebSearchTool.SearchReport ghRate = pubTool.executeDetailed(Map.of("query", "spring", "site", "github.com"));
            assertFalse(ghRate.ok());
            assertTrue(ghRate.error().contains("每分钟限流"), ghRate.error());

            WebSearchTool.SearchReport arxivRes = pubTool.executeDetailed(
                Map.of("query", "Mixture of Experts", "category", "science"));
            assertTrue(arxivRes.ok());
            assertTrue(arxivQuery.get().contains("all:Mixture+AND+all:of+AND+all:Experts"), arxivQuery.get());
            assertTrue(arxivRes.formattedText().contains("OpenAlex Mixture of Experts Paper"));

            WebSearchTool.SearchReport hnRes = pubTool.executeDetailed(
                Map.of("query", "Zero-Key Local Search", "category", "news"));
            assertTrue(hnRes.ok());
            assertTrue(hnRes.formattedText().contains("Show HN: Zero-Key Local Search"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchRejectsLocalAndNonHttpUrls() {
        WebFetchTool tool = new WebFetchTool(HttpClient.newHttpClient());
        assertTrue(tool.execute(Map.of("url", "http://127.0.0.1/secret")).startsWith("ERROR:"));
        assertTrue(tool.execute(Map.of("url", "file:///etc/passwd")).startsWith("ERROR:"));
    }

    @Test
    void fetchDoesNotFollowRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            // 本机地址在发出请求前就拒绝，不会顺着 Location 再访问一次
            String result = new WebFetchTool(HttpClient.newHttpClient())
                .execute(Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertTrue(result.startsWith("ERROR:"), result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchFollowsSafeRedirectsExtractsArticleGbkAndBlocksPrivateRedirectTargets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/article");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/article", exchange -> {
            String filler = "这是来自中文 GBK 页面正文的重要技术说明，用于验证正文提取与编码识别能力。".repeat(8);
            String html = "<html><head><meta charset=\"gbk\"><title>中文文档 &amp; 指南</title></head>"
                + "<body><nav>导航条干扰内容</nav><article><h1>核心章节</h1><p>" + filler + "</p></article>"
                + "<footer>页脚版权声明</footer></body></html>";
            byte[] bytes = html.getBytes(Charset.forName("GB18030"));
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/to-private", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:9/admin");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop-a", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop-b");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop-b", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop-a");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/json", exchange -> {
            byte[] bytes = "{\"status\":\"ok\",\"tag\":\"<raw>\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/gzip-og", exchange -> {
            String html = "\uFEFF<html><head><title></title><meta property=\"og:title\" content=\"Spring Virtual Threads\"></head>"
                + "<body><main><p>Gzip compressed SPA documentation body content.</p></main></body></html>";
            ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(gzOut)) {
                gz.write(html.getBytes(StandardCharsets.UTF_8));
            }
            byte[] compressed = gzOut.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            // Safe redirect + GBK + <article> prioritization
            WebFetchTool safeFetch = new WebFetchTool(client, uri -> {
            });
            String article = safeFetch.execute(Map.of("url", base + "/start", "max_chars", 1500));
            assertTrue(article.contains("来源: " + base + "/article"));
            assertTrue(article.contains("重定向"));
            assertTrue(article.contains("标题: 中文文档 & 指南"));
            assertTrue(article.contains("核心章节"));
            assertFalse(article.contains("导航条干扰内容"));
            assertFalse(article.contains("页脚版权声明"));

            // Gzip + empty <title> falls back to og:title + strips UTF-8 BOM
            String gzipOut = safeFetch.execute(Map.of("url", base + "/gzip-og"));
            assertTrue(gzipOut.contains("标题: Spring Virtual Threads"), gzipOut);
            assertTrue(gzipOut.contains("Gzip compressed SPA documentation body content."), gzipOut);
            assertFalse(gzipOut.contains("\uFEFF"));

            // JSON preserves raw payload without stripping angle brackets
            String jsonOut = safeFetch.execute(Map.of("url", base + "/json"));
            assertTrue(jsonOut.contains("\"<raw>\""));

            // Redirect loop rejected
            String loopOut = safeFetch.execute(Map.of("url", base + "/loop-a"));
            assertTrue(loopOut.contains("重定向循环"), loopOut);

            // Real SSRF validator applied on redirect hop blocks loopback target
            AtomicInteger calls = new AtomicInteger();
            WebFetchTool ssrfCheckOnRedirect = new WebFetchTool(client, uri -> {
                if (calls.getAndIncrement() > 0) {
                    WebFetchTool.validate(uri);
                }
            });
            String blocked = ssrfCheckOnRedirect.execute(Map.of("url", base + "/to-private"));
            assertTrue(blocked.startsWith("ERROR: 重定向目标被拒绝"), blocked);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchHandlesNestedArticleBlocksSpaHintSharedRedirectTimeoutAndParsesFinalUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nested", exchange -> {
            String tail = "这是嵌套卡片之后仍然属于主文章的正文尾部关键内容，绝对不能被非贪婪正则截断。".repeat(6);
            String html = "<html><head><title>嵌套文章</title></head><body><nav><nav>多层导航噪声</nav></nav>"
                + "<article><p>文章开头段落。</p><article><p>内嵌引用卡片</p></article><p>" + tail + "</p></article>"
                + "<footer>底部版权</footer></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/spa", exchange -> {
            String html = "<html><head><title>SPA</title><script>" + "var x=1;".repeat(200)
                + "</script></head><body><div id=\"root\"></div></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/slow-1", exchange -> {
            try {
                Thread.sleep(260L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseHeaders().set("Location", "/slow-2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow-2", exchange -> {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "too late".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            WebFetchTool tool = new WebFetchTool(client, uri -> {
            });
            String nested = tool.execute(Map.of("url", base + "/nested"));
            assertTrue(nested.contains("文章开头段落"), nested);
            assertTrue(nested.contains("绝对不能被非贪婪正则截断"), nested);
            assertFalse(nested.contains("多层导航噪声"), nested);

            String spa = tool.execute(Map.of("url", base + "/spa"));
            assertTrue(spa.contains("JavaScript 动态渲染"), spa);

            // 共享总预算 400ms：两跳合计 > 500ms 应超时而非每跳重算
            WebFetchTool bounded = new WebFetchTool(client, uri -> {
            }, Duration.ofMillis(400));
            String timedOut = bounded.execute(Map.of("url", base + "/slow-1"));
            assertTrue(timedOut.contains("超时"), timedOut);

            assertEquals("https://spring.io/blog/post/",
                WebFetchTool.parseFetchedSourceUrl(
                    "来源: https://spring.io/blog/post/（由 https://spring.io/blog/post 重定向）\n标题: Hi"));
        } finally {
            server.stop(0);
        }
    }
}
