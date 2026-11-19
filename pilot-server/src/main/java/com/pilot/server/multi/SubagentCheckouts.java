package com.pilot.server.multi;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 一个父运行里的独立检出。最多 4 个；创建失败时不提供目录，调用方不得改父工作区。
 */
public final class SubagentCheckouts {
    public static final int MAX_OPEN = 4;

    public record Opened(Path path, String diff, String error) {
        public boolean ok() {
            return error == null || error.isBlank();
        }
    }

    @FunctionalInterface
    public interface Opener {
        Opened open(Path workspace);
    }

    private final Opener opener;
    private final Deque<Path> open = new ArrayDeque<>();

    public SubagentCheckouts(Opener opener) {
        this.opener = opener;
    }

    public synchronized Opened acquire(Path workspace) {
        if (open.size() >= MAX_OPEN) {
            return new Opened(null, "", "独立检出已有 " + MAX_OPEN + " 个，不能再创建");
        }
        Opened opened = opener.open(workspace);
        if (opened == null || !opened.ok()) {
            String error =
                opened == null || opened.error() == null || opened.error().isBlank() ? "无法创建独立检出" : opened.error();
            return new Opened(null, "", error + "。未修改父工作区。");
        }
        open.addLast(opened.path());
        return opened;
    }

    public synchronized void release(Path path) {
        open.remove(path);
    }

    public synchronized int openCount() {
        return open.size();
    }
}
