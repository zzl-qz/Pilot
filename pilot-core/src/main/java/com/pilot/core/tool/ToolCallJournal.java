package com.pilot.core.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工具调用幂等账本。
 *
 * <p>重启接管后，模型可能重新提出「和崩溃前完全一样」的写文件调用。如果直接再执行一次，
 * 会把已经写好的文件再覆盖一遍；如果内容相同倒是无害，但当文件又被用户改过时就会覆盖人工修改。
 * 账本记录「本次运行里这个调用成功过、写出的内容指纹是什么」，重放前再核对文件现状，
 * 只有仍与记录一致时才复用结果。
 *
 * <p>只对写文件类工具启用重放：命令重跑是预期行为（测试要重新执行），不能当成重复调用丢弃。
 */
public interface ToolCallJournal {

    /**
     * @param fingerprint 工具名 + 参数规范化后的哈希
     * @param postHash    写完之后的文件内容指纹；无法计算时为空串
     * @param replayable  是否允许复用（只有写文件成功的结果为 true）
     */
    record Recorded(String result, List<String> changedPaths, List<String> checks, String fingerprint, String postHash,
        boolean replayable) {
        public Recorded {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
            checks = checks == null ? List.of() : List.copyOf(checks);
            result = result == null ? "" : result;
            postHash = postHash == null ? "" : postHash;
        }
    }

    /** 没有记录、或记录不再适用时返回空，调用方正常执行。 */
    Optional<Recorded> replay(String runId, ToolCall call);

    /** 实现方按自己的方式计算写出内容的现状指纹（服务端知道工作区路径）。 */
    void record(String runId, ToolCall call, String result, List<String> changedPaths, List<String> checks,
        boolean replayable);

    int deleteBySession(String sessionId);

    static ToolCallJournal none() {
        return new ToolCallJournal() {
            @Override
            public Optional<Recorded> replay(String runId, ToolCall call) {
                return Optional.empty();
            }

            @Override
            public void record(String runId, ToolCall call, String result, List<String> changedPaths,
                List<String> checks, boolean replayable) {
            }

            @Override
            public int deleteBySession(String sessionId) {
                return 0;
            }
        };
    }

    /** 工具名 + 规范化参数（键排序、去空白）的 SHA-256，忽略 callId。 */
    static String fingerprint(ToolCall call) {
        String canonical = call.name() + "\0" + canonical(call.args());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + "=" + canonical(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ToolCallJournal::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value).strip();
    }
}
