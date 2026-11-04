package com.pilot.server.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pilot.server.config.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

/**
 * 会话级校验证据账本。
 *
 * <p>解决的问题是「报告通过，但产物已经变了」：完成声明必须能对应一条仍覆盖当前产物的证据。
 * 证据按候选指纹保存，查询时用当前指纹比对，因此旧证据不会在代码变化后继续生效。
 *
 * <p>落盘为会话级 JSON（{@code .pilot/validation/<session>.json}），保留最近 {@value #MAX_ENTRIES} 条；
 * 写盘失败只降级为「本次会话诊断不可复核」，不阻断任务。
 */
@Component
public class ValidationLedger {
    private static final Logger log = LoggerFactory.getLogger(ValidationLedger.class);
    static final int MAX_ENTRIES = 50;

    /**
     * @param current 最近一条证据是否覆盖当前产物
     * @param reason  不可用的原因；可用时为空串
     */
    public record Report(boolean current, String reason, String currentCandidateHash, ValidationEvidence latest,
        List<ValidationEvidence> recent) {
        public Report {
            reason = reason == null ? "" : reason;
            recent = recent == null ? List.of() : List.copyOf(recent);
        }
    }

    private record Stored(String sessionId, List<ValidationEvidence> entries) {
        private Stored {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private final PilotDataDir dataDir;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, List<ValidationEvidence>> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> loaded = new ConcurrentHashMap<>();

    public ValidationLedger(PilotDataDir dataDir, ObjectMapper mapper) {
        this.dataDir = dataDir;
        this.mapper = mapper;
    }

    public synchronized void record(ValidationEvidence evidence) {
        if (evidence == null || evidence.sessionId() == null) {
            return;
        }
        List<ValidationEvidence> entries = new ArrayList<>(recent(evidence.sessionId()));
        entries.add(evidence);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        cache.put(evidence.sessionId(), List.copyOf(entries));
        persist(evidence.sessionId());
    }

    public List<ValidationEvidence> recent(String sessionId) {
        load(sessionId);
        return cache.getOrDefault(sessionId, List.of());
    }

    /** 当前产物是否有可用的通过证据。 */
    public Report report(String sessionId, String currentCandidateHash) {
        List<ValidationEvidence> entries = recent(sessionId);
        if (entries.isEmpty()) {
            return new Report(false, "还没有任何校验证据", currentCandidateHash, null, List.of());
        }
        ValidationEvidence latest = entries.getLast();
        if (currentCandidateHash == null || currentCandidateHash.isBlank()) {
            return new Report(false, "本轮没有产物变化，无需校验", "", latest, entries);
        }
        if (!currentCandidateHash.equals(latest.candidateHash())) {
            return new Report(false, "最近一次校验针对的是旧产物，代码已变化", currentCandidateHash, latest, entries);
        }
        if (!latest.passed()) {
            return new Report(false, "最近一次校验没有通过（" + latest.status() + "）", currentCandidateHash, latest, entries);
        }
        return new Report(true, "", currentCandidateHash, latest, entries);
    }

    public int deleteBySession(String sessionId) {
        cache.remove(sessionId);
        loaded.remove(sessionId);
        Path file = file(sessionId);
        try {
            return Files.deleteIfExists(file) ? 1 : 0;
        } catch (IOException e) {
            log.warn("删除校验证据失败: {}", file, e);
            return 0;
        }
    }

    private void load(String sessionId) {
        if (sessionId == null || loaded.putIfAbsent(sessionId, true) != null) {
            return;
        }
        Path file = file(sessionId);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Stored stored = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            if (stored != null && stored.entries() != null) {
                cache.put(sessionId, List.copyOf(stored.entries()));
            }
        } catch (Exception e) {
            log.warn("忽略损坏的校验证据文件: {}", file, e);
        }
    }

    private void persist(String sessionId) {
        Path file = file(sessionId);
        try {
            Files.createDirectories(file.getParent());
            String json = mapper.writeValueAsString(new Stored(sessionId, recent(sessionId)));
            Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            log.warn("写入校验证据失败: {}", file, e);
        }
    }

    private Path file(String sessionId) {
        return dataDir.file("validation", sanitize(sessionId) + ".json");
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
