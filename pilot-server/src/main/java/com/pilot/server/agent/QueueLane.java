package com.pilot.server.agent;

import java.util.regex.Pattern;

import com.pilot.core.util.Texts;

/**
 * 排队消息里哪些会在本轮结束后自动发出去。
 * 范围外、没写对象的点击或命名、以及收尾时才提交，都不进入自动发送。
 */
final class QueueLane {
    private static final Pattern QUOTED = Pattern.compile("[「『\"“'][^」』\"”']{1,40}[」』\"”']");
    private static final Pattern NAMED_CONTROL =
        Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]{2,16}(按钮|图标|链接|菜单|输入框|弹窗)");
    private static final Pattern VAGUE_CONTROL = Pattern.compile("(这个|那个|这里的?)(按钮|图标|链接|菜单|输入框|弹窗)");

    private QueueLane() {
    }

    static boolean sendsAutomatically(String content) {
        return "send".equals(lane(content));
    }

    static String lane(String content) {
        String body = Texts.oneLine(content, Integer.MAX_VALUE);
        if (body.isEmpty()) {
            return "send";
        }
        if (body.startsWith("遗留") || body.contains("不在本次范围")) {
            return "parked";
        }
        if (isWrapup(body)) {
            return "wrapup";
        }
        if (isVagueClick(body) || isVagueName(body)) {
            return "blocked";
        }
        return "send";
    }

    private static boolean isWrapup(String body) {
        boolean ships = containsAny(body, "提交", "commit", "Commit") && containsAny(body, "推送", "push", "Push");
        boolean splits = body.contains("按功能") || body.contains("拆分多个") || body.toLowerCase().contains("多个 commit")
            || body.toLowerCase().contains("多个commit");
        return ships && (body.contains("做完") || body.contains("记得") || body.contains("收尾") || splits);
    }

    private static boolean isVagueClick(String body) {
        if (!containsAny(body, "点击", "点了", "点一下", "点之后", "点这里", "点这儿")) {
            return false;
        }
        if (!containsAny(body, "为啥", "为什么", "不太行", "不对", "修复", "修一下", "怎样", "这样")) {
            return false;
        }
        return !namesAThing(body);
    }

    private static boolean isVagueName(String body) {
        if (!containsAny(body, "命名", "名字", "叫法")) {
            return false;
        }
        if (!containsAny(body, "奇怪", "不对", "不好", "别扭", "改一下", "改改")) {
            return false;
        }
        return !namesAThing(body);
    }

    private static boolean namesAThing(String body) {
        if (QUOTED.matcher(body).find()) {
            return true;
        }
        return NAMED_CONTROL.matcher(body).find() && !VAGUE_CONTROL.matcher(body).find();
    }

    private static boolean containsAny(String body, String... parts) {
        for (String part : parts) {
            if (body.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
