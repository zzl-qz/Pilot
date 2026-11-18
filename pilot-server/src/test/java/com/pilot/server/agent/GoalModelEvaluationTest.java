package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Cancellation;
import com.pilot.core.message.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.OpenAiCompatibleModelClient;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.quality.IndependentCodeReviewer;
import com.pilot.core.quality.WorkspaceTexts;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UpdatePlanTool;
import com.pilot.core.tool.WritesFiles;
import com.pilot.core.tool.command.RunCommandTool;
import com.pilot.core.tool.file.ApplyPatchTool;
import com.pilot.core.tool.file.CreateFileTool;
import com.pilot.core.tool.file.EditFileTool;
import com.pilot.core.tool.file.GlobFilesTool;
import com.pilot.core.tool.file.ListDirTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.tool.file.SearchTool;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in: sends only synthetic fixtures to the selected endpoint. */
class GoalModelEvaluationTest {
    @TempDir
    Path root;
    final JsonMapper mapper = JsonMapper.builder().build();

    ModelClient model() {
        return new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), mapper,
            URI.create(Objects.requireNonNull(System.getenv("PILOT_EVAL_ENDPOINT"), "set PILOT_EVAL_ENDPOINT")),
            System.getenv("PILOT_EVAL_API_KEY"),
            Objects.requireNonNull(System.getenv("PILOT_EVAL_MODEL"), "set PILOT_EVAL_MODEL"), Duration.ofSeconds(90));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PILOT_EVAL_API_KEY", matches = ".+")
    void measuresMainAgentGoalPromotionWithoutASeparateRuntimeClassifier() throws Exception {
        String[] direct = {"将 README.md 标题 demo 改为 Demo", "将 config.json 的 port 改为8081，保留其他字段",
            "解释 cart.py 的 calculate 参数，不改代码", "给 config.json 增加 timezone=UTC", "只给 calculate 补一句函数注释",
            "将 README.md 的错字修正", "在 README.md 末尾增加启动命令 python3 cart.py", "将 config.json 的 timeout 改为20",
            "分析图片和文字混合消息为什么会在队列里丢附件，可以参考公开产品；只给修复方案，等我确认后再改代码", "在 README.md 将 demo 全部替换为 sample，完成后停止"};
        String[] goals = {"持续排查偶发结算金额错误，定位根因并修复，以复现与回归证据证明问题解决", "实现完整优惠券结算功能，贯通计算、参数验证和错误处理，直到集成验证通过",
            "持续优化结算性能直到基准 p95 低于10ms，保持结果兼容", "追查并修复并发重试偶发重复扣款，覆盖重试与恢复路径", "把旧结算接口完整迁移到新协议，验证所有调用方兼容",
            "持续调查随机失败的结算测试，直到可靠复现并修复", "完成支付与退款集成，覆盖失败重试与补偿，验证用户完整流程", "研究结算金额不一致问题，交付有证据的根因和修复结果",
            "持续消除结算内存增长，基准运行稳定后才结束", "完整交付可使用的折扣模块，覆盖接口、存储与前端联调，验证所有关键行为"};
        String[] uncertain = {"修一下算错钱的问题", "为什么会偶发卡住，帮我解决", "让这个结算模块真正可用", "优惠券加免运费有问题，排查修复", "升级依赖并保证程序还能正常运行",
            "这里重构一下，要保持兼容", "这个功能还是很浅，把缺的补齐", "登录偶发失败，找出原因处理好", "帮我提升性能", "继续，把之前没完成的集成做好"};
        List<Map<String, Object>> rows = new ArrayList<>();
        int mismatches = 0;
        for (int i = 0; i < 30; i++) {
            String prompt = i < 10 ? direct[i] : i < 20 ? goals[i - 10] : uncertain[i - 20];
            Path work = Files.createDirectory(root.resolve("route" + i));
            fixture(work);
            TaskControlService tasks =
                new TaskControlService(mapper, new PilotDataDir(root.resolve("data" + i).toString()));
            tasks.start("s", prompt, List.of(), List.of());
            GoalExecutionPolicy policy = new GoalExecutionPolicy(model(), mapper, tasks, "s", work, () -> false, 10);
            policy.beforeRequest(List.of(Message.system(AgentLoop.DEFAULT_SYSTEM_PROMPT), Message.user(prompt)));
            Tool promote = policy.promotionTool();
            long started = System.nanoTime();
            ModelResponse choice =
                model().chat(List.of(Message.system(AgentLoop.DEFAULT_SYSTEM_PROMPT), Message.user(prompt)),
                    List.of(new ToolDefinition(promote.name(), promote.description(), promote.parameters())));
            choice.toolCalls().stream().filter(call -> call.name().equals("promote_goal")).findFirst()
                .ifPresent(call -> promote.execute(call.args()));
            String mode = tasks.load("s").execution().mode();
            boolean matched = i < 10 ? mode.equals("DIRECT") : i < 20 ? mode.equals("GOAL") : true;
            if (!matched) {
                mismatches++;
            }
            rows.add(Map.of("prompt", prompt, "mode", mode, "matched", matched, "latencyMs",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/goal-routing-evaluation.json"), mapper.writeValueAsString(rows));
        assertTrue(mismatches <= 2, "明确请求误分超过2项，请检查报告；模糊请求仅记录，不声称存在唯一正确分类");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PILOT_EVAL_API_KEY", matches = ".+")
    void comparesLegacyAndGoalWithIndependentAcceptance() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (boolean enabled : List.of(false, true)) {
            for (int scenario = 0; scenario < 3; scenario++) {
                Path work = Files.createDirectory(root.resolve("work" + enabled + scenario));
                fixture(work);
                String prompt = switch (scenario) {
                    case 0 -> "只将 README.md 第一行的 demo 改为 Demo，保留其他内容。";
                    case 1 -> "将 config.json 的 port 改为8081，timeout 保持10。";
                    default ->
                        "持续排查 cart.py 在优惠券和运费组合下算错金额的问题，修复根因并覆盖关键边界，保持 calculate(amount, coupon) API。规则：优惠后满100免运费，否则运费10；优惠后金额最低为0。请验证结果，直到目标完成。";
                };
                TaskControlService tasks = new TaskControlService(mapper,
                    new PilotDataDir(root.resolve("state" + enabled + scenario).toString()));
                tasks.start("s", prompt, List.of(), List.of());
                GoalExecutionPolicy policy =
                    new GoalExecutionPolicy(model(), mapper, tasks, "s", work, () -> false, 30);
                AtomicCounter count = new AtomicCounter(model());
                ModelClient runtime = enabled ? policy.budgetedClient() : count;
                PathValidator paths = new PathValidator(work);
                ToolRegistry tools = new ToolRegistry();
                tools.register(new ReadFileTool(paths));
                tools.register(new SearchTool(paths));
                tools.register(new ListDirTool(paths));
                tools.register(new EditFileTool(paths));
                tools.register(new CreateFileTool(paths));
                tools.register(new RunCommandTool(paths));
                tools.register(new UpdatePlanTool((items, reason) -> {
                }));
                if (enabled) {
                    tools.register(policy.promotionTool());
                }
                AgentLoop loop = new AgentLoop(runtime, tools, new PolicyPermissionGate(PermissionMode.AUTO),
                    ApprovalBroker.denyAll(), new AgentLoop.Config(30, 3, AgentLoop.DEFAULT_SYSTEM_PROMPT),
                    Cancellation.none()).withTaskContext(() -> tasks.prompt("s"));
                if (enabled) {
                    loop.withExecutionPolicy(policy);
                }
                long started = System.nanoTime();
                AgentLoop.Result result = loop.run(prompt, List.of(), event -> {
                });
                boolean accepted = acceptance(work, scenario);
                rows.add(Map.of("goalEnabled", enabled, "scenario", scenario, "accepted", accepted, "declaredComplete",
                    !result.failed(), "failure", Objects.toString(result.failureCode(), ""), "modelCalls",
                    enabled ? tasks.load("s").execution().requests() : count.calls, "durationMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/goal-outcome-evaluation.json"), mapper.writeValueAsString(rows));
        assertTrue(rows.stream().filter(row -> Boolean.TRUE.equals(row.get("goalEnabled")))
            .allMatch(row -> Boolean.TRUE.equals(row.get("accepted"))), "新模式未满足独立验收，检查报告");
    }

    void fixture(Path work) throws Exception {
        Files.writeString(work.resolve("README.md"), "demo\nA tiny cart fixture.\n");
        Files.writeString(work.resolve("config.json"), "{\"port\":8080,\"timeout\":10}");
        Files.writeString(work.resolve("cart.py"),
            "def calculate(amount, coupon):\n    net = max(0, amount - coupon)\n    return net + (0 if amount >= 100 else 10)\n");
    }

    boolean acceptance(Path work, int scenario) throws Exception {
        if (scenario == 0) {
            return Files.readString(work.resolve("README.md")).equals("Demo\nA tiny cart fixture.\n");
        }
        if (scenario == 1) {
            JsonNode config = mapper.readTree(Files.readString(work.resolve("config.json")));
            return config.path("port").asInt() == 8081 && config.path("timeout").asInt() == 10;
        }
        Process p = new ProcessBuilder("python3", "-c",
            "from cart import calculate; assert calculate(120,30)==100; assert calculate(150,30)==120; assert calculate(10,50)==10; assert calculate(100,0)==100")
            .directory(work.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    static class AtomicCounter implements ModelClient {
        final ModelClient delegate;
        int calls;

        AtomicCounter(ModelClient delegate) {
            this.delegate = delegate;
        }

        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            calls++;
            return delegate.chat(messages, tools);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "pilot.eval.live", matches = "true")
    void comparesCodeQualityGovernanceOnRealModelTask() throws Exception {
        ModelClient rawModel = localConfiguredModel();
        String taskPrompt =
            """
                请在 src/main/java/com/mall/order/SettlementService.java 中实现采购结算服务 SettlementService，并在 src/test/java 下补写普通 main 方法测试类 `SettlementServiceTest`（用 assert 断言），然后执行 `./mvnw test` 验证通过：
                1. public SettlementReceipt settleNormal(List<OrderItem> orders)：只结算状态为 "PAID" 的订单；单批传入超过 50 笔订单时抛 IllegalArgumentException；每笔已支付订单按 discountRate 计算折后金额（四舍五入到分，Math.round(amountCents * discountRate)）并累加；签名使用 "normal:" + paidCount + ":" + totalCents 的 SHA-256 小写十六进制字符串；返回的 SettlementReceipt 中锁过期时间设为 86400 秒。
                2. public SettlementReceipt settleUrgent(List<OrderItem> orders)：同样只结算状态为 "PAID" 的订单、超过 50 笔抛 IllegalArgumentException、每笔按 discountRate 算折后金额，但每笔已支付订单额外加收 500 分加急服务费；签名使用 "urgent:" + paidCount + ":" + totalCents 的 SHA-256 小写十六进制字符串；锁过期时间同样为 86400 秒。
                3. public boolean verifyWebhook(WebhookPayload webhookPayload, String secret)：当外部回调报文 webhookPayload != null 且 webhookPayload.returnCode() 为 "SUCCESS"，同时 webhookPayload.signature() 等于 (webhookPayload.orderNo() + ":" + secret) 的 SHA-256 小写十六进制摘要时返回 true，否则返回 false。
                """;

        Map<String, Object> baselineReport = runQualityTrial(rawModel, taskPrompt, false);
        Map<String, Object> governedReport = runQualityTrial(rawModel, taskPrompt, true);

        Map<String, Object> full = new LinkedHashMap<>();
        full.put("baselineWithoutQualityEngine", baselineReport);
        full.put("pilotWithQualityEngine", governedReport);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/code-quality-ab-evaluation.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(full));

        assertTrue(Boolean.TRUE.equals(governedReport.get("functionalAcceptancePassed")),
            "开启质量治理后的实现必须通过独立外部功能验收：" + governedReport);
    }

    private Map<String, Object> runQualityTrial(ModelClient rawModel, String prompt, boolean qualityEnabled)
        throws Exception {
        Path work =
            Files.createDirectory(root.resolve(qualityEnabled ? "governed-mall" : "baseline-mall")).toRealPath();
        mallFixture(work);
        Path snapDir = Files.createDirectory(root.resolve(qualityEnabled ? "snap-gov" : "snap-base")).toRealPath();
        RunSnapshotService snapshots = new RunSnapshotService(mapper, new PilotDataDir(snapDir.toString()));
        String runId = qualityEnabled ? "run_governed" : "run_baseline";
        snapshots.bind(runId, work);

        AtomicCounter counter = new AtomicCounter(rawModel);
        PathValidator paths = new PathValidator(work);
        ReadTracker reads = new ReadTracker();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ReadFileTool(paths, reads));
        tools.register(new ListDirTool(paths));
        tools.register(new GlobFilesTool(paths));
        tools.register(new SearchTool(paths));
        Tool edit = new EditFileTool(paths, snapshots, reads);
        Tool create = new CreateFileTool(paths, snapshots, reads);
        Tool patch = new ApplyPatchTool(paths, snapshots, reads);
        tools.register(qualityEnabled ? edit : stripQualityHint(edit));
        tools.register(qualityEnabled ? create : stripQualityHint(create));
        tools.register(qualityEnabled ? patch : stripQualityHint(patch));
        tools.register(new RunCommandTool(paths));
        tools.register(new UpdatePlanTool((items, reason) -> {
        }));

        PromptLibrary prompts = PromptLibrary.standard();
        String systemPrompt = qualityEnabled ? prompts.system(PromptLibrary.environment("mall"), tools.names())
            : "你是编码代理，在本地工作区通过工具读写文件并运行命令完成用户要求的开发任务，完成后简要汇报。";

        List<Message> seedHistory = new ArrayList<>();
        if (qualityEnabled) {
            String repoBrief =
                prompts.render("context/repository", Map.of("tree", "- pom.xml\n- mvnw\n- src/\n- README.md", "git",
                    "工作区干净", "build", "Maven 项目：优先用工作区的 `./mvnw test` 验证编译与测试", "lint", "未发现 lint 命令")).strip();
            seedHistory.add(Message.user(repoBrief));
        }

        List<String> continuations = new ArrayList<>();
        List<String> inlineQualityHints = new ArrayList<>();
        List<String> toolTrace = new ArrayList<>();
        AgentLoop loop = new AgentLoop(counter, tools, new PolicyPermissionGate(PermissionMode.AUTO),
            ApprovalBroker.denyAll(),
            new AgentLoop.Config(25, 3, 5, systemPrompt, List::of, 2, 180_000, qualityEnabled), Cancellation.none());
        if (qualityEnabled) {
            loop.withRestoredPaths(snapshots::restoredPaths).withQualityWorkspace(work)
                .withQualityTexts(snapshots::originalText, p -> WorkspaceTexts.read(work, p))
                .withIndependentCodeReviewer(new IndependentCodeReviewer(counter, work, () -> false));
        }

        long started = System.nanoTime();
        AgentLoop.Result result;
        try {
            result = loop.run(prompt, List.of(), List.of(), seedHistory, event -> {
                if (event instanceof RunContinued cont) {
                    continuations.add(cont.reason());
                } else if (event instanceof ToolStarted ts) {
                    toolTrace.add(ts.name() + ": " + ts.summary());
                }
            }, msgs -> {
                for (Message m : msgs) {
                    if ("tool".equals(m.role()) && m.content() != null && m.content().contains("[代码质量提醒]")) {
                        if (!inlineQualityHints.contains(m.content())) {
                            inlineQualityHints.add(m.content());
                        }
                    }
                }
            });
        } finally {
            snapshots.finish();
            snapshots.clear();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        Path targetFile = work.resolve("src/main/java/com/mall/order/SettlementService.java");
        String code = Files.isRegularFile(targetFile) ? Files.readString(targetFile) : "";
        String acceptanceError = verifyMallAcceptance(work);
        boolean passedAcceptance = acceptanceError.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("qualityEngineEnabled", qualityEnabled);
        report.put("declaredComplete", !result.failed());
        report.put("failureCode", result.failureCode());
        report.put("functionalAcceptancePassed", passedAcceptance);
        report.put("acceptanceError", acceptanceError);
        report.put("modelRequests", counter.calls);
        report.put("durationMs", durationMs);
        report.put("inlineHintsTriggered", inlineQualityHints.size());
        report.put("inlineHints", inlineQualityHints);
        report.put("completionContinuations", continuations);
        report.put("toolTrace", toolTrace);
        report.put("reusedSignUtils", code.contains("SignUtils"));
        report.put("handRolledMessageDigest", code.contains("MessageDigest"));
        report.put("reusedMoneyUtils", code.contains("MoneyUtils"));
        report.put("reusedOrderStatusEnum", code.contains("OrderStatus"));
        report.put("wronglyCoupledInvoiceStatus", code.contains("InvoiceStatus"));
        report.put("reusedCacheConstantsTtl",
            code.contains("CacheConstants") || code.contains("ORDER_LOCK_TTL_SECONDS"));
        report.put("wronglyCoupledPageSize50", code.contains("PageConstants") || code.contains("DEFAULT_PAGE_SIZE"));
        report.put("remainingQualityFindings", 0);
        report.put("remainingFindingDetails", List.of());
        report.put("generatedCode", code);
        return report;
    }

    private static Tool stripQualityHint(Tool delegate) {
        if (delegate instanceof WritesFiles wf) {
            return new StripWriteTool(delegate, wf);
        }
        return delegate;
    }

    private record StripWriteTool(Tool delegate, WritesFiles wf) implements Tool, WritesFiles {
        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public Map<String, Object> parameters() {
            return delegate.parameters();
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public String execute(Map<String, Object> args) {
            return strip(delegate.execute(args));
        }

        @Override
        public com.pilot.core.tool.ToolResult executeResult(Map<String, Object> args) {
            var res = delegate.executeResult(args);
            return new com.pilot.core.tool.ToolResult(strip(res.text()), res.changedPaths(), res.checks(),
                res.images());
        }

        @Override
        public String changedPath(com.pilot.core.message.ToolCall call) {
            return wf.changedPath(call);
        }

        private static String strip(String text) {
            int idx = text == null ? -1 : text.indexOf("\n\n[代码质量提醒]");
            return idx < 0 ? text : text.substring(0, idx);
        }
    }

    private ModelClient localConfiguredModel() throws Exception {
        String envKey = System.getenv("PILOT_EVAL_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return model();
        }
        Path db = Files.isRegularFile(Path.of("../.pilot/pilot.db")) ? Path.of("../.pilot/pilot.db")
            : Path.of(".pilot/pilot.db");
        Map<String, String> cfg = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath().normalize());
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT key, value FROM settings WHERE key LIKE 'model.%'")) {
            while (rs.next()) {
                cfg.put(rs.getString(1), rs.getString(2));
            }
        }
        String endpoint =
            Objects.requireNonNull(cfg.get("model.endpoint"), "missing model.endpoint in .pilot/pilot.db");
        String modelName = Objects.requireNonNull(cfg.get("model.name"), "missing model.name in .pilot/pilot.db");
        String apiKey = Objects.requireNonNull(cfg.get("model.api-key"), "missing model.api-key in .pilot/pilot.db");
        return new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), mapper, URI.create(endpoint), apiKey,
            modelName, Duration.ofSeconds(120));
    }

    private static void mallFixture(Path work) throws Exception {
        Files.writeString(work.resolve("README.md"), "# Mall Backend\nRun `./mvnw test` to compile and run tests.\n");
        Files.writeString(work.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.mall</groupId><artifactId>mall</artifactId><version>1.0</version></project>\n");
        String javaBin = Path.of(System.getProperty("java.home"), "bin").toString();
        Path mvnw = work.resolve("mvnw");
        Files.writeString(mvnw,
            """
                #!/bin/sh
                set -e
                JAVA_BIN="%s"
                mkdir -p target/classes target/test-classes
                "$JAVA_BIN/javac" -d target/classes $(find src/main/java -name "*.java")
                if [ -d src/test/java ] && [ -n "$(find src/test/java -name '*.java')" ]; then
                  "$JAVA_BIN/javac" -cp target/classes -d target/test-classes $(find src/test/java -name "*.java")
                  for cls in $(cd target/test-classes && find . -name "*Test.class" | sed 's|^./||;s|/|.|g;s|\\.class$||'); do
                    "$JAVA_BIN/java" -ea -cp target/classes:target/test-classes "$cls"
                  done
                fi
                echo "BUILD SUCCESS"
                """
                .formatted(javaBin));
        mvnw.toFile().setExecutable(true);
        writeTestJava(work, "com/mall/order/SmokeTest.java", """
            package com.mall.order;
            public class SmokeTest {
                public static void main(String[] args) {
                    assert OrderStatus.PAID.name().equals("PAID");
                }
            }
            """);
        writeJava(work, "com/mall/common/util/SignUtils.java", """
            package com.mall.common.util;
            import java.nio.charset.StandardCharsets;
            import java.security.MessageDigest;
            import java.util.HexFormat;
            public final class SignUtils {
                private SignUtils() {}
                public static String sha256Hex(String payload) {
                    try {
                        byte[] digest = MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8));
                        return HexFormat.of().formatHex(digest);
                    } catch (Exception e) {
                        throw new IllegalStateException("sha256 failed", e);
                    }
                }
            }
            """);
        writeJava(work, "com/mall/common/util/MoneyUtils.java", """
            package com.mall.common.util;
            public final class MoneyUtils {
                private MoneyUtils() {}
                public static long applyRate(long amountCents, double rate) {
                    return Math.round(amountCents * rate);
                }
            }
            """);
        writeJava(work, "com/mall/common/constants/CacheConstants.java", """
            package com.mall.common.constants;
            public final class CacheConstants {
                private CacheConstants() {}
                public static final int ORDER_LOCK_TTL_SECONDS = 86400;
            }
            """);
        writeJava(work, "com/mall/common/constants/PageConstants.java", """
            package com.mall.common.constants;
            public final class PageConstants {
                private PageConstants() {}
                public static final int DEFAULT_PAGE_SIZE = 50;
            }
            """);
        writeJava(work, "com/mall/invoice/InvoiceStatus.java", """
            package com.mall.invoice;
            public enum InvoiceStatus {
                DRAFT,
                PAID,
                VOID
            }
            """);
        writeJava(work, "com/mall/shipping/WaybillService.java", """
            package com.mall.shipping;
            public class WaybillService {
                public String dispatch(String no) { return "SHIP:" + no; }
            }
            """);
        writeJava(work, "com/mall/catalog/SkuItem.java", """
            package com.mall.catalog;
            public record SkuItem(String sku, long priceCents) {}
            """);
        writeJava(work, "com/mall/order/OrderStatus.java", """
            package com.mall.order;
            public enum OrderStatus {
                CREATED,
                PAID,
                CANCELLED
            }
            """);
        writeJava(work, "com/mall/order/OrderItem.java", """
            package com.mall.order;
            public record OrderItem(String orderNo, String status, long amountCents, double discountRate) {}
            """);
        writeJava(work, "com/mall/order/WebhookPayload.java", """
            package com.mall.order;
            public record WebhookPayload(String orderNo, String returnCode, String signature) {}
            """);
        writeJava(work, "com/mall/order/SettlementReceipt.java", """
            package com.mall.order;
            public record SettlementReceipt(int count, long totalCents, int lockTtlSeconds, String signature) {}
            """);
    }

    private static void writeJava(Path work, String relPkgPath, String content) throws Exception {
        Path file = work.resolve("src/main/java").resolve(relPkgPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void writeTestJava(Path work, String relPkgPath, String content) throws Exception {
        Path file = work.resolve("src/test/java").resolve(relPkgPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String verifyMallAcceptance(Path work) throws Exception {
        Path verifyDir = Files.createTempDirectory("mall-verify-");
        Path outDir = verifyDir.resolve("classes");
        Files.createDirectories(outDir);
        Path checker = verifyDir.resolve("AcceptanceMain.java");
        Files.writeString(checker, """
            public class AcceptanceMain {
                public static void main(String[] args) {
                    var svc = new com.mall.order.SettlementService();
                    var items = java.util.List.of(
                        new com.mall.order.OrderItem("O1", "PAID", 1000L, 0.85),
                        new com.mall.order.OrderItem("O2", "CREATED", 5000L, 1.0),
                        new com.mall.order.OrderItem("O3", "PAID", 2000L, 0.90)
                    );
                    var normal = svc.settleNormal(items);
                    if (normal.count() != 2 || normal.totalCents() != 2650L || normal.lockTtlSeconds() != 86400) {
                        throw new AssertionError("normal mismatch: " + normal);
                    }
                    if (!com.mall.common.util.SignUtils.sha256Hex("normal:2:2650").equals(normal.signature())) {
                        throw new AssertionError("normal sig mismatch: " + normal.signature());
                    }
                    var urgent = svc.settleUrgent(items);
                    if (urgent.count() != 2 || urgent.totalCents() != 3650L || urgent.lockTtlSeconds() != 86400) {
                        throw new AssertionError("urgent mismatch: " + urgent);
                    }
                    if (!com.mall.common.util.SignUtils.sha256Hex("urgent:2:3650").equals(urgent.signature())) {
                        throw new AssertionError("urgent sig mismatch: " + urgent.signature());
                    }
                    var tooMany = new java.util.ArrayList<com.mall.order.OrderItem>();
                    for (int i = 0; i < 51; i++) {
                        tooMany.add(new com.mall.order.OrderItem("N" + i, "PAID", 100L, 1.0));
                    }
                    boolean threw = false;
                    try {
                        svc.settleNormal(tooMany);
                    } catch (IllegalArgumentException expected) {
                        threw = true;
                    }
                    if (!threw) {
                        throw new AssertionError("expected IllegalArgumentException for > 50 items");
                    }
                    String okSig = com.mall.common.util.SignUtils.sha256Hex("O1:sec123");
                    if (!svc.verifyWebhook(new com.mall.order.WebhookPayload("O1", "SUCCESS", okSig), "sec123")) {
                        throw new AssertionError("valid webhook rejected");
                    }
                    if (svc.verifyWebhook(new com.mall.order.WebhookPayload("O1", "FAIL", okSig), "sec123")) {
                        throw new AssertionError("failed returnCode accepted");
                    }
                    if (svc.verifyWebhook(new com.mall.order.WebhookPayload("O1", "SUCCESS", "bad"), "sec123")) {
                        throw new AssertionError("bad signature accepted");
                    }
                }
            }
            """);

        List<String> compileCmd = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        compileCmd.add(Path.of(javaHome, "bin", "javac").toString());
        compileCmd.add("-d");
        compileCmd.add(outDir.toString());
        try (var stream = Files.walk(work.resolve("src/main/java"))) {
            stream.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().endsWith("Test.java"))
                .map(Path::toString).forEach(compileCmd::add);
        }
        compileCmd.add(checker.toString());
        Process compile = new ProcessBuilder(compileCmd).redirectErrorStream(true).start();
        String compileOut = new String(compile.getInputStream().readAllBytes());
        if (!compile.waitFor(20, TimeUnit.SECONDS) || compile.exitValue() != 0) {
            return "COMPILE_FAILED: " + compileOut;
        }
        Process run =
            new ProcessBuilder(Path.of(javaHome, "bin", "java").toString(), "-cp", outDir.toString(), "AcceptanceMain")
                .redirectErrorStream(true).start();
        String runOut = new String(run.getInputStream().readAllBytes());
        return run.waitFor(10, TimeUnit.SECONDS) && run.exitValue() == 0 ? "" : "RUN_FAILED: " + runOut;
    }
}
