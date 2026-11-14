package com.pilot.server.run;

/**
 * {@code /review} 独立审查模式的识别与范围解析。
 *
 * <p>消息以 {@code /review} 开头（词边界，{@code /reviewx} 不算）即进入审查 run：
 * 工具表被裁剪为只读（读/列/搜/命令，写文件与派发工具一律不存在），
 * 系统提示词追加固定审查协议（{@code prompts/review/protocol.md}），
 * 权限语义与普通 run 完全一致（只读命令按档位放行，写命令照旧审批）。
 *
 * <p>识别在 server 层做而不是前端展开：用户直接输入 {@code /review 重点看登录} 也一样生效，
 * 不依赖命令面板入口。
 */
public record ReviewMode(boolean active, String scope) {

    private static final String PREFIX = "/review";

    /** @param input 用户消息原文；null 安全 */
    public static ReviewMode parse(String input) {
        String text = input == null ? "" : input.strip();
        if (!text.startsWith(PREFIX)) {
            return new ReviewMode(false, "");
        }
        String rest = text.substring(PREFIX.length());
        // /reviewx 之类的前缀撞车不算审查；/review 后跟空白或结尾才算
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return new ReviewMode(false, "");
        }
        return new ReviewMode(true, rest.strip());
    }

    /** 审查范围说明：未显式给范围时默认审未提交改动，注入协议模板的 {@code {{scope}}}。 */
    public String scopeText() {
        return scope.isEmpty() ? "当前工作区的未提交改动（以 git status 与 git diff 实查为准）" : scope;
    }
}
