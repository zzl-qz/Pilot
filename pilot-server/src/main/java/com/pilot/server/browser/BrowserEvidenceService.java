package com.pilot.server.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pilot.core.browser.BrowserEvidenceRecord;
import com.pilot.core.browser.BrowserEvidenceRecorder;
import com.pilot.core.tool.ToolArtifacts;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 浏览器宿主机证据持久化与检索服务：按会话保存在 {@code <dataDir>/browser-evidence/<sessionId>.json}，
 * 供实时面板回看、截图定位反馈及 {@code CompletionBundleService} 交付汇总。
 */
@Service
@Slf4j
public class BrowserEvidenceService implements BrowserEvidenceRecorder {
    static final int MAX_RECORDS_PER_SESSION = 200;
    private static final TypeReference<List<BrowserEvidenceRecord>> RECORD_LIST = new TypeReference<>() {};

    private final Path directory;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<BrowserEvidenceRecord>> listeners = new CopyOnWriteArrayList<>();

    @Autowired
    public BrowserEvidenceService(PilotDataDir dataDir, ObjectMapper mapper) {
        this(dataDir.file("browser-evidence"), mapper);
    }

    public BrowserEvidenceService(Path directory, ObjectMapper mapper) {
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public void addListener(Consumer<BrowserEvidenceRecord> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Consumer<BrowserEvidenceRecord> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void record(BrowserEvidenceRecord record) {
        if (record == null || record.sessionId().isBlank()) {
            return;
        }
        String sessionId = record.sessionId();
        synchronized (lockFor(sessionId)) {
            List<BrowserEvidenceRecord> entries = new ArrayList<>(readAll(sessionId));
            entries.add(record);
            if (entries.size() > MAX_RECORDS_PER_SESSION) {
                entries = new ArrayList<>(entries.subList(entries.size() - MAX_RECORDS_PER_SESSION, entries.size()));
            }
            writeAll(sessionId, entries);
        }
        for (Consumer<BrowserEvidenceRecord> listener : listeners) {
            try {
                listener.accept(record);
            } catch (RuntimeException ignored) {
                // 广播监听异常不影响落盘
            }
        }
    }

    public List<BrowserEvidenceRecord> list(String sessionId, String runId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<BrowserEvidenceRecord> all;
        synchronized (lockFor(sessionId)) {
            all = readAll(sessionId);
        }
        String runFilter = runId == null ? "" : runId.strip();
        List<BrowserEvidenceRecord> filtered =
            all.stream().filter(item -> runFilter.isEmpty() || runFilter.equals(item.runId()))
                .sorted(Comparator.comparing(BrowserEvidenceRecord::timestamp).reversed()).toList();
        int cap = limit <= 0 ? filtered.size() : Math.min(limit, filtered.size());
        return List.copyOf(filtered.subList(0, cap));
    }

    public Optional<BrowserEvidenceRecord> find(String sessionId, String evidenceId) {
        if (sessionId == null || sessionId.isBlank() || evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lockFor(sessionId)) {
            return readAll(sessionId).stream().filter(item -> evidenceId.equals(item.id())).findFirst();
        }
    }

    /** 仅允许读取属于本会话证据记录且真实落在受控产物目录下的截图文件。 */
    public Optional<Path> resolveScreenshot(String sessionId, String evidenceId) {
        return resolveArtifact(sessionId, evidenceId, BrowserEvidenceRecord::screenshotPath);
    }

    /** 仅允许读取属于本会话证据记录且真实落在受控产物目录下的过程录像文件。 */
    public Optional<Path> resolveRecording(String sessionId, String evidenceId) {
        return resolveArtifact(sessionId, evidenceId, BrowserEvidenceRecord::recordingPath);
    }

    private Optional<Path> resolveArtifact(String sessionId, String evidenceId,
        Function<BrowserEvidenceRecord, String> extractor) {
        Optional<BrowserEvidenceRecord> found = find(sessionId, evidenceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String rawPath = extractor.apply(found.get());
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        Path browserArtifactsRoot = ToolArtifacts.directory("browser").toAbsolutePath().normalize();
        if (!candidate.startsWith(browserArtifactsRoot) && !candidate.startsWith(directory)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    public Map<String, Object> summary(String sessionId, String runId) {
        List<BrowserEvidenceRecord> entries = list(sessionId, runId, 0);
        int screenshots = 0;
        int recordings = 0;
        int downloads = 0;
        int errors = 0;
        int handoffs = 0;
        Set<String> blockedOrigins = new LinkedHashSet<>();
        for (BrowserEvidenceRecord item : entries) {
            if (!item.screenshotPath().isBlank()) {
                screenshots++;
            }
            if (!item.recordingPath().isBlank()) {
                recordings++;
            }
            if (!item.downloadName().isBlank() || !item.downloadPath().isBlank()) {
                downloads++;
            }
            if ("error".equals(item.status()) || "blocked".equals(item.status()) || item.consoleErrors() > 0
                || item.failedRequests() > 0) {
                errors++;
            }
            if ("handoff".equals(item.action()) || !item.gateKind().isBlank()) {
                handoffs++;
            }
            blockedOrigins.addAll(item.blockedOrigins());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", entries.size());
        result.put("screenshots", screenshots);
        result.put("recordings", recordings);
        result.put("downloads", downloads);
        result.put("errors", errors);
        result.put("handoffs", handoffs);
        result.put("blockedOrigins", List.copyOf(blockedOrigins));
        return result;
    }

    public void deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        synchronized (lockFor(sessionId)) {
            try {
                Files.deleteIfExists(fileFor(sessionId));
            } catch (IOException e) {
                log.warn("删除浏览器证据失败 session={}: {}", sessionId, e.getMessage());
            }
        }
    }

    private List<BrowserEvidenceRecord> readAll(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<BrowserEvidenceRecord> loaded = mapper.readValue(file.toFile(), RECORD_LIST);
            return loaded == null ? List.of() : loaded;
        } catch (RuntimeException e) {
            log.warn("读取浏览器证据失败 {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    private void writeAll(String sessionId, List<BrowserEvidenceRecord> records) {
        try {
            Files.createDirectories(directory);
            Path target = fileFor(sessionId);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), records);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("写入浏览器证据失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private Path fileFor(String sessionId) {
        String safe = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path file = directory.resolve(safe + ".json").toAbsolutePath().normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("非法会话编号: " + sessionId);
        }
        return file;
    }

    private Object lockFor(String sessionId) {
        return locks.computeIfAbsent(sessionId, ignored -> new Object());
    }
}
