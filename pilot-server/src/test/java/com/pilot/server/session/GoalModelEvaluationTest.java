package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Cancellation;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.OpenAiCompatibleModelClient;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.CreateFileTool;
import com.pilot.core.tool.EditFileTool;
import com.pilot.core.tool.ListDirTool;
import com.pilot.core.tool.ReadFileTool;
import com.pilot.core.tool.RunCommandTool;
import com.pilot.core.tool.SearchTool;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.UpdatePlanTool;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.common.data.PilotDataDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in: sends only synthetic fixtures to the selected endpoint. */
@EnabledIfEnvironmentVariable(named = "PILOT_EVAL_API_KEY", matches = ".+")
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
}
