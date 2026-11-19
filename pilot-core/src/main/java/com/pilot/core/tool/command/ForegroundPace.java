package com.pilot.core.tool.command;

import java.util.Locale;

/**
 * 前台命令要不要继续占着这一轮。
 * 几秒内失败由调用方单独处理。这里只区分「还在正常变慢」和「没输出，像卡住了」。
 */
final class ForegroundPace {
    static final long SILENCE_LIMIT_MS = 30_000L;
    static final long HOLD_LIMIT_MS = 120_000L;
    static final long QUICK_FAIL_MS = 8_000L;

    private ForegroundPace() {
    }

    enum Release {
        NONE, SILENT, STILL_WORKING
    }

    /** 测试、构建这类本来就会跑很久。普通命令没有新输出时不给这个宽限。 */
    static boolean expectsLong(String command) {
        String text = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (text.contains("go test") || text.contains("cargo test") || text.contains("pytest")
            || text.contains("dotnet test") || text.contains("node --test")) {
            return true;
        }
        if (text.contains("npm test") || text.contains("pnpm test") || text.contains("yarn test")
            || text.contains("npm run test") || text.contains("pnpm run test") || text.contains("npm run build")
            || text.contains("pnpm run build") || text.contains("yarn build") || text.contains("playwright")
            || text.contains("vitest") || text.contains("jest") || text.contains("cypress")) {
            return true;
        }
        boolean buildTool = text.contains("mvn") || text.contains("gradle");
        boolean buildGoal = text.contains("test") || text.contains("verify") || text.contains("package")
            || text.contains("install") || text.contains("build");
        return buildTool && buildGoal;
    }

    static Release release(boolean longCommand, long quietMs, long heldMs, long silenceLimitMs, long holdLimitMs) {
        if (!longCommand && quietMs >= silenceLimitMs) {
            return Release.SILENT;
        }
        if (heldMs >= holdLimitMs) {
            return Release.STILL_WORKING;
        }
        return Release.NONE;
    }
}
