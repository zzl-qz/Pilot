package com.pilot.server.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.maintenance.StorageHealth;
import com.pilot.server.store.SqliteStore;

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

    private record State(List<ValidationEvidence> entries, String issue) {
    }

    private final PilotDataDir dataDir;
    private final ObjectMapper mapper;
    private final SqliteStore sqlite;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    @Autowired
    public ValidationLedger(PilotDataDir dataDir, ObjectMapper mapper, ObjectProvider<SqliteStore> store) {
        this(dataDir, mapper, store.getIfAvailable());
    }

    public ValidationLedger(PilotDataDir dataDir, ObjectMapper mapper) {
        this(dataDir, mapper, (SqliteStore)null);
    }

    private ValidationLedger(PilotDataDir dataDir, ObjectMapper mapper, SqliteStore sqlite) {
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.sqlite = sqlite;
    }

    @PostConstruct
    void reportPendingWrites() {
        try {
            Path directory = dataDir.file("validation");
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".pending"))
                        .map(name -> name.substring(0, name.length() - ".pending".length())).forEach(this::state);
                }
            }
            if (sqlite != null) {
                sqlite.pendingValidationSessions().forEach(this::state);
            }
        } catch (IOException | RuntimeException e) {
            StorageHealth.markDegraded("validation", "读取待保存的校验证据失败：" + e.getMessage());
        }
    }

    public synchronized void record(ValidationEvidence evidence) {
        if (evidence == null || evidence.sessionId() == null) {
            return;
        }
        String sessionId = evidence.sessionId();
        List<ValidationEvidence> previous = recent(sessionId);
        states.put(sessionId, new State(previous, "证据正在写入"));
        try {
            beginUpdate(sessionId);
            List<ValidationEvidence> entries = new ArrayList<>(previous);
            entries.add(evidence);
            if (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
            persist(sessionId, entries);
            finishUpdate(sessionId);
            states.put(sessionId, new State(List.copyOf(entries), null));
            recoverHealth();
        } catch (IOException | RuntimeException e) {
            log.warn("写入校验证据失败: {}", file(sessionId), e);
            states.put(sessionId, failed(sessionId, previous, "校验证据未能可靠落盘：" + e.getMessage()));
        }
    }

    public List<ValidationEvidence> recent(String sessionId) {
        return state(sessionId).entries();
    }

    private State state(String sessionId) {
        return sessionId == null ? new State(List.of(), null) : states.computeIfAbsent(sessionId, this::load);
    }

    /** 当前产物是否有可用的通过证据。 */
    public Report report(String sessionId, String currentCandidateHash) {
        State state = state(sessionId);
        List<ValidationEvidence> entries = state.entries();
        if (state.issue() != null) {
            return new Report(false, state.issue(), currentCandidateHash, entries.isEmpty() ? null : entries.getLast(),
                entries);
        }
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

    public synchronized int deleteBySession(String sessionId) {
        Path file = file(sessionId);
        List<ValidationEvidence> previous = recent(sessionId);
        states.put(sessionId, new State(previous, "证据正在删除"));
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                beginUpdate(sessionId);
            }
            int removed = Files.deleteIfExists(file) ? 1 : 0;
            finishUpdate(sessionId);
            states.remove(sessionId);
            recoverHealth();
            return removed;
        } catch (IOException | RuntimeException e) {
            log.warn("删除校验证据失败: {}", file, e);
            states.put(sessionId, failed(sessionId, previous, "删除校验证据失败：" + e.getMessage()));
            return 0;
        }
    }

    public synchronized int purgeOlderThan(Instant cutoff, Set<String> activeSessions) {
        Path directory = dataDir.file("validation");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json")) {
                    String id = name.substring(0, name.length() - ".json".length());
                    if (!activeSessions.contains(id) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        removed += deleteBySession(id);
                    }
                }
            }
        } catch (IOException e) {
            StorageHealth.markDegraded("validation", "清理过期证据失败：" + e.getMessage());
            log.warn("清理过期证据失败", e);
        }
        return removed;
    }

    private State load(String sessionId) {
        Path file = file(sessionId);
        try {
            List<ValidationEvidence> entries = List.of();
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Stored stored = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
                if (stored == null || !sessionId.equals(stored.sessionId())) {
                    throw new IOException("会话与证据文件不匹配");
                }
                entries = stored.entries();
            }
            if (Files.exists(marker(sessionId), LinkOption.NOFOLLOW_LINKS)
                || sqlite != null && sqlite.validationPending(sessionId)) {
                return failed(sessionId, entries, "上一次校验证据未能可靠落盘");
            }
            return new State(entries, null);
        } catch (IOException | RuntimeException e) {
            log.warn("读取校验证据失败: {}", file, e);
            return failed(sessionId, List.of(), "证据文件损坏或不可读");
        }
    }

    private State failed(String sessionId, List<ValidationEvidence> entries, String reason) {
        StorageHealth.markDegraded("validation", sessionId + " " + reason);
        return new State(entries, reason);
    }

    private void recoverHealth() {
        if (states.values().stream().noneMatch(state -> state.issue() != null)) {
            StorageHealth.recovered("validation");
        }
    }

    /** 先留下写入意图，即使旧记录仍在，重启后也能识别未完成的写入。 */
    private void beginUpdate(String sessionId) throws IOException {
        Path marker = marker(sessionId);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "pending", StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            if (sqlite != null) {
                sqlite.setValidationPending(sessionId, true);
            }
            throw e;
        }
    }

    private void finishUpdate(String sessionId) throws IOException {
        if (sqlite != null && sqlite.validationPending(sessionId)) {
            sqlite.setValidationPending(sessionId, false);
        }
        Files.deleteIfExists(marker(sessionId));
    }

    private Path marker(String sessionId) {
        return dataDir.file("validation", sanitize(sessionId) + ".pending");
    }

    private void persist(String sessionId, List<ValidationEvidence> entries) throws IOException {
        Path file = file(sessionId);
        Path temporary = Files.createTempFile(file.getParent(), "validation-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(new Stored(sessionId, entries)),
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(String sessionId) {
        return dataDir.file("validation", sanitize(sessionId) + ".json");
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
