package com.pilot.core.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 插件与核心之间的契约表。
 *
 * <p>插件失效的根因不是「核心变了」，而是「插件绑在了实现上」。这里把插件真正依赖的东西
 * 抽成少数几个有版本的契约：技能声明自己要用哪些工具、钩子声明自己要挂哪些事件，
 * 核心升级时比对的是契约，而不是让插件跟着版本号跑。
 *
 * <p>兼容规则只有两条：
 *
 * <ul>
 *   <li>新增字段、新增可选能力：<strong>不算变更</strong>，不升大版本；</li>
 *   <li>删除字段、改变语义或默认行为：<strong>必须升大版本</strong>，旧插件按不兼容处理。</li>
 * </ul>
 *
 * <p>因此插件写 {@code tool.read_file@1}，只要核心还提供 v1 语义就一直可用，
 * 不会因为核心从 1.x 走到 2.x 就被打掉。
 */
public final class Contracts {
    /** 一个已发布的契约。{@code id} 不带版本；引用时写成 {@code id@major}。 */
    public record Contract(String id, int major, String since, String summary) {
        public String reference() {
            return id + "@" + major;
        }
    }

    /** 一次声明检查的结果。{@code satisfied} 为真时 {@code missing} 必然为空。 */
    public record Check(boolean satisfied, List<String> missing, List<String> unknown) {
        public Check {
            missing = missing == null ? List.of() : List.copyOf(missing);
            unknown = unknown == null ? List.of() : List.copyOf(unknown);
        }

        public static Check ok() {
            return new Check(true, List.of(), List.of());
        }

        /** 给用户看的一句话；不说「契约」这种术语，只说哪个东西用不了。 */
        public String describe() {
            if (satisfied) {
                return "";
            }
            if (!unknown.isEmpty()) {
                return "声明了核心不认识的契约：" + String.join("、", unknown);
            }
            return "核心当前不提供：" + String.join("、", missing);
        }
    }

    private static final Map<String, Contract> PUBLISHED = published();

    private static Map<String, Contract> published() {
        Map<String, Contract> map = new TreeMap<>();
        for (Contract contract : List.of(new Contract("tool.read_file", 1, "1.0", "读取工作区文件，返回文本或图片"),
            new Contract("tool.list_dir", 1, "1.0", "列目录"), new Contract("tool.glob_files", 1, "1.0", "按路径模式找文件"),
            new Contract("tool.search_text", 1, "1.0", "按内容搜索文件"),
            new Contract("tool.edit_file", 1, "1.0", "按唯一匹配替换文件片段"), new Contract("tool.create_file", 1, "1.0", "创建文件"),
            new Contract("tool.apply_patch", 1, "1.0", "应用跨文件补丁"),
            new Contract("tool.run_command", 1, "1.0", "执行命令并返回退出码与输出"),
            new Contract("tool.update_plan", 1, "1.0", "维护执行计划"),
            new Contract("hook.pre_tool_use", 1, "1.0", "工具执行前的确定性钩子"),
            new Contract("hook.post_tool_use", 1, "1.0", "工具执行后的确定性钩子"),
            new Contract("event.run_end", 1, "1.0", "一次运行结束事件"), new Contract("event.tool_result", 1, "1.0", "工具结果事件"),
            new Contract("skill.frontmatter", 1, "1.0", "SKILL.md 的 frontmatter 字段与语义"))) {
            map.put(contract.id(), contract);
        }
        return Map.copyOf(map);
    }

    /** 核心当前发布的所有契约，按 id 排序。 */
    public static List<Contract> publishedContracts() {
        return List.copyOf(PUBLISHED.values());
    }

    public static Optional<Contract> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(PUBLISHED.get(id.strip()));
    }

    /**
     * 检查一组声明是否都被当前核心满足。
     *
     * <p>写法不合法（没有版本号、版本号不是数字）按「未知」处理，而不是当作满足——
     * 拿不准的时候降级，不能放大成「可以加载」。
     */
    public static Check check(List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return Check.ok();
        }
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : declared) {
            String reference = raw == null ? "" : raw.strip();
            int at = reference.lastIndexOf('@');
            if (at <= 0 || at == reference.length() - 1) {
                unknown.add(reference);
                continue;
            }
            String id = reference.substring(0, at).strip();
            int major;
            try {
                major = Integer.parseInt(reference.substring(at + 1).strip());
            } catch (NumberFormatException malformed) {
                unknown.add(reference);
                continue;
            }
            Optional<Contract> contract = find(id);
            if (contract.isEmpty()) {
                unknown.add(reference);
            } else if (contract.get().major() != major) {
                missing.add(reference);
            }
        }
        if (missing.isEmpty() && unknown.isEmpty()) {
            return Check.ok();
        }
        return new Check(false, missing, unknown);
    }

    /** 供诊断页展示的对照表：{@code id@major → 说明}。 */
    public static Map<String, String> catalogue() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Contract contract : publishedContracts()) {
            map.put(contract.reference(), contract.summary());
        }
        return map;
    }

    private Contracts() {
    }
}
