package com.pilot.core.lint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只认本次新加的抑制注释。文件里原来就有的不算，避免旧代码把任务卡住。
 */
final class SuppressionScan {
    private static final List<String> MARKERS = List.of("eslint-disable", "biome-ignore", "@ts-ignore", "@ts-nocheck",
        "@ts-expect-error", "noqa", "type: ignore", "pyright: ignore", "nolint", "@suppresswarnings", "nosonar",
        "noinspection", "rubocop:disable", "phpcs:disable", "phpcs:ignore", "stylelint-disable", "prettier-ignore",
        "checkstyle:suppress");

    private SuppressionScan() {
    }

    static List<String> added(String before, String after) {
        Map<String, Integer> existing = new LinkedHashMap<>();
        for (String line : lines(before)) {
            if (isSuppression(line)) {
                existing.merge(line.strip(), 1, Integer::sum);
            }
        }
        List<String> added = new ArrayList<>();
        for (String line : lines(after)) {
            if (!isSuppression(line)) {
                continue;
            }
            String key = line.strip();
            int left = existing.getOrDefault(key, 0);
            if (left > 0) {
                existing.put(key, left - 1);
            } else if (added.size() < 8) {
                added.add(key);
            }
        }
        return List.copyOf(added);
    }

    private static boolean isSuppression(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\\R"));
    }
}
