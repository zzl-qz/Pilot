package com.pilot.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        List<String> added = new ArrayList<>();
        BuiltinLint.diffAdded(before, after, SuppressionScan::isSuppression, (number, key) -> {
            if (added.size() < 8) {
                added.add(key);
            }
        });
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
}
