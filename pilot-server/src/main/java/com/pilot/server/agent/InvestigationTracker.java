package com.pilot.server.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.pilot.core.loop.ToolExecutor;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.VerificationCheck;
import com.pilot.core.tool.code.CodeSymbolTool;
import com.pilot.core.util.Sha256;

/**
 * 基于边际信息增益的调查有效度追踪器（无持久化、单次运行生命周期）。
 *
 * <p>不阻断任何只读工具，只在滑动窗口内边际新信息枯竭（空搜索、重叠重读、无新文件命中、重复报错）
 * 或实现类任务跨大量文件持续观察仍零编辑/验证时，给出带真实统计的温和收敛提醒。
 */
final class InvestigationTracker {
    static final int WINDOW_SIZE = 6;
    static final int MIN_OBSERVATIONS_FOR_LOW_GAIN = 6;
    static final int ZERO_GAIN_STREAK_AT = 4;
    static final int WINDOW_ZERO_GAIN_AT = 5;
    static final int LOW_GAIN_COOLDOWN = 4;
    static final int BROAD_NUDGE_FIRST_AT = 14;
    static final int BROAD_NUDGE_STEP = 6;
    static final int BROAD_NUDGE_MIN_FILES = 8;
    /** 即使只围绕少数核心文件，持续零编辑观察达到此值也应发出非阻断收敛检查点。 */
    static final int PROLONGED_NO_EDIT_AT = 14;
    /** 同一文件在本阶段被反复读取/检索达到该次数，记为高频热点文件。 */
    static final int HOTSPOT_FILE_OBS = 4;
    private static final int DISPLAY_HOTSPOT_MIN_OBS = 3;
    private static final int MAX_DISPLAY_HOTSPOTS = 3;
    private static final int MAX_DISPLAY_READ_FILES = 6;
    private static final double OVERLAP_ZERO_GAIN_RATIO = 0.50;

    private static final Set<String> PURE_OBSERVATION_TOOLS = Set.of(ToolNames.READ_FILE, ToolNames.SEARCH_TEXT,
        ToolNames.GLOB_FILES, ToolNames.LIST_DIR, CodeSymbolTool.NAME, ToolNames.READ_TOOL_OUTPUT);
    private static final Pattern READ_FOOTER = Pattern.compile("显示第\\s+(\\d+)\\s+到第\\s+(\\d+)\\s+行");
    private static final Pattern SEARCH_FILES_ONLY = Pattern.compile("^(.+?)\\s+\\(\\d+\\s*处匹配\\)$");
    private static final Pattern SEARCH_CONTENT_LINE = Pattern.compile("^(.+?)[:-]\\d+[:-]\\s.*$");

    enum ZeroKind {
        DEDUP, EMPTY_SEARCH, OVERLAP_READ, REDUNDANT_HIT, ERROR
    }

    record Nudge(String summary, String action, boolean lowGain, String diagnostics) {
        Nudge(String summary, String action, boolean lowGain) {
            this(summary, action, lowGain, "");
        }
    }

    private record Entry(boolean gained, ZeroKind zeroKind) {
    }

    private final Deque<Entry> window = new ArrayDeque<>();
    private final Set<String> seenFiles = new LinkedHashSet<>();
    private final Set<String> readFiles = new LinkedHashSet<>();
    private final Map<String, Integer> fileObservationCounts = new LinkedHashMap<>();
    private final Map<String, List<ReadGuard.LineRange>> readRangesByFile = new HashMap<>();
    private final Set<String> inspectedTargets = new HashSet<>();
    private final Set<String> commandOutputHashes = new HashSet<>();
    private final Set<String> verificationHashes = new HashSet<>();
    private int phaseObservations;
    private int zeroGainStreak;
    private int lastLowGainNudgeAt = -LOW_GAIN_COOLDOWN;
    private int nextBroadNudgeAt = BROAD_NUDGE_FIRST_AT;

    synchronized void reset() {
        window.clear();
        seenFiles.clear();
        readFiles.clear();
        fileObservationCounts.clear();
        readRangesByFile.clear();
        inspectedTargets.clear();
        commandOutputHashes.clear();
        phaseObservations = 0;
        zeroGainStreak = 0;
        lastLowGainNudgeAt = -LOW_GAIN_COOLDOWN;
        nextBroadNudgeAt = BROAD_NUDGE_FIRST_AT;
    }

    synchronized void observe(ToolCall call, ToolExecutor.Outcome outcome) {
        if (call == null || outcome == null) {
            return;
        }
        // 1. 实质性文件写入立即开启新阶段
        if (!outcome.changedPaths().isEmpty()
            || (ToolNames.FILE_EDITS.contains(call.name()) && outcome.status() == ToolExecutor.OutcomeStatus.SUCCESS)) {
            verificationHashes.clear();
            reset();
            return;
        }
        // 2. 有效验证通过，或首次获得新的构建/测试失败复现信息，均视作有效推进
        if (isResettingVerification(outcome)) {
            reset();
            return;
        }
        boolean readOnlyCommand = ToolNames.RUN_COMMAND.equals(call.name()) && outcome.checks().isEmpty()
            && outcome.status() != ToolExecutor.OutcomeStatus.RUNNING;
        if (!PURE_OBSERVATION_TOOLS.contains(call.name()) && !readOnlyCommand) {
            return;
        }

        Entry entry = classifyObservation(call, outcome, readOnlyCommand);
        recordFileObservation(call);
        phaseObservations++;
        window.addLast(entry);
        if (window.size() > WINDOW_SIZE) {
            window.removeFirst();
        }
        zeroGainStreak = entry.gained() ? 0 : zeroGainStreak + 1;
    }

    synchronized Nudge nextNudge(boolean readOnlyOrResearch) {
        long windowZero = window.stream().filter(entry -> !entry.gained()).count();
        boolean lowGainHit = phaseObservations >= MIN_OBSERVATIONS_FOR_LOW_GAIN
            && (zeroGainStreak >= ZERO_GAIN_STREAK_AT
                || (window.size() >= WINDOW_SIZE && windowZero >= WINDOW_ZERO_GAIN_AT))
            && phaseObservations - lastLowGainNudgeAt >= LOW_GAIN_COOLDOWN;
        if (lowGainHit) {
            lastLowGainNudgeAt = phaseObservations;
            String breakdown = formatBreakdown();
            String hotspots = formatHotspots();
            if (readOnlyOrResearch) {
                String summary = "本阶段最近 " + window.size() + " 次只读查询中有 " + windowZero + " 次未产生新文件或新线索"
                    + (breakdown.isEmpty() ? "" : "（" + breakdown + "）")
                    + (hotspots.isEmpty() ? "。" : "；高频反复查看：" + hotspots + "。");
                String action = "当前为分析/只读任务：请先基于已有证据收束结论，或明确指出代码库中缺失的信息与核验缺口，不要继续重复同方向空搜。";
                return new Nudge(summary, action, true, breakdown);
            }
            if (readFiles.isEmpty()) {
                String summary = "本阶段最近 " + window.size() + " 次只读查询中有 " + windowZero + " 次未产生新线索"
                    + (breakdown.isEmpty() ? "" : "（" + breakdown + "）") + "，尚未读取目标文件或开始修改。";
                String action = "不要继续重复同方向空搜：请改换路径或关键词定位目标文件后立即开始修改，或直接运行最贴近的单测/复现命令获取真实报错堆栈。";
                return new Nudge(summary, action, true, breakdown);
            }
            return implementationEditCheckpoint(true, breakdown, hotspots);
        }
        long hotspotCount = fileObservationCounts.values().stream().filter(c -> c >= HOTSPOT_FILE_OBS).count();
        boolean broadOrProlonged = phaseObservations >= nextBroadNudgeAt && (readFiles.size() >= BROAD_NUDGE_MIN_FILES
            || phaseObservations >= PROLONGED_NO_EDIT_AT || hotspotCount >= 2);
        if (!readOnlyOrResearch && broadOrProlonged) {
            nextBroadNudgeAt = phaseObservations + BROAD_NUDGE_STEP;
            return implementationEditCheckpoint(false, "", formatHotspots());
        }
        return null;
    }

    private Nudge implementationEditCheckpoint(boolean lowGain, String breakdown, String hotspots) {
        String readList = formatReadFiles();
        StringBuilder scope = new StringBuilder("跨 ").append(readFiles.size()).append(" 个文件");
        if (!readList.isEmpty()) {
            scope.append("，已读完 ").append(readList);
        }
        if (!hotspots.isEmpty()) {
            scope.append("；高频反复查看：").append(hotspots);
        }
        String summary = "你已经连续做了 " + phaseObservations + " 次只读查询（" + scope + "），但还没有改过一行代码。";
        String action =
            "现在必须停止只读查询：下一步直接调用 apply_patch、edit_file 或 create_file 开始修改目标文件，改完后再通过跑单测验证边界和调用方（不要先猜完所有次要文件再动手）。";
        return new Nudge(summary, action, lowGain, breakdown);
    }

    synchronized int phaseObservations() {
        return phaseObservations;
    }

    synchronized int distinctReadFiles() {
        return readFiles.size();
    }

    private boolean isResettingVerification(ToolExecutor.Outcome outcome) {
        if (outcome.checks().isEmpty()) {
            return false;
        }
        boolean anyVerification = false;
        for (String check : outcome.checks()) {
            if (VerificationCheck.passingKind(check).qualifies()) {
                return true;
            }
            String command = stripExitCode(check);
            if (VerificationCheck.classifyCommand(command.toLowerCase()).qualifies()) {
                anyVerification = true;
            }
        }
        if (!anyVerification) {
            return false;
        }
        String signature =
            Sha256.hex(String.join("\n", outcome.checks()) + "\n" + Objects.toString(outcome.result(), ""));
        return verificationHashes.add(signature);
    }

    private Entry classifyObservation(ToolCall call, ToolExecutor.Outcome outcome, boolean readOnlyCommand) {
        String result = Objects.toString(outcome.result(), "").strip();
        if (result.startsWith("文件未变化") || result.startsWith("与上次相同，未再执行")) {
            return new Entry(false, ZeroKind.DEDUP);
        }
        if (isFailureOutcome(result, outcome)) {
            return new Entry(false, ZeroKind.ERROR);
        }
        if (result.isBlank() || outcome.status() == ToolExecutor.OutcomeStatus.EMPTY) {
            return new Entry(false, ZeroKind.EMPTY_SEARCH);
        }
        return switch (call.name()) {
            case ToolNames.READ_FILE -> classifyRead(call, result);
            case ToolNames.SEARCH_TEXT -> classifySearch(result);
            case ToolNames.GLOB_FILES -> classifyGlob(result);
            case ToolNames.LIST_DIR, CodeSymbolTool.NAME -> classifyTargetInspection(call);
            case ToolNames.READ_TOOL_OUTPUT -> classifyToolOutputRead(call);
            default -> readOnlyCommand ? classifyReadOnlyCommand(result) : new Entry(true, null);
        };
    }

    private static boolean isFailureOutcome(String result, ToolExecutor.Outcome outcome) {
        return result.startsWith("ERROR:") || outcome.status() == ToolExecutor.OutcomeStatus.PERMANENT_FAILURE
            || outcome.status() == ToolExecutor.OutcomeStatus.TRANSIENT_FAILURE
            || outcome.status() == ToolExecutor.OutcomeStatus.DENIED
            || outcome.status() == ToolExecutor.OutcomeStatus.SKIPPED;
    }

    private Entry classifyTargetInspection(ToolCall call) {
        String target = normalizePath(arg(call, "path"));
        if (CodeSymbolTool.NAME.equals(call.name()) && !target.isEmpty()) {
            seenFiles.add(target);
        }
        return inspectedTargets.add(call.name() + ":" + target) ? new Entry(true, null)
            : new Entry(false, ZeroKind.REDUNDANT_HIT);
    }

    private Entry classifyToolOutputRead(ToolCall call) {
        String key = call.name() + ":" + arg(call, "run_id") + ":" + arg(call, "call_id") + ":" + arg(call, "offset");
        return inspectedTargets.add(key) ? new Entry(true, null) : new Entry(false, ZeroKind.REDUNDANT_HIT);
    }

    private Entry classifyReadOnlyCommand(String result) {
        return commandOutputHashes.add(Sha256.hex(result)) ? new Entry(true, null)
            : new Entry(false, ZeroKind.REDUNDANT_HIT);
    }

    private Entry classifyRead(ToolCall call, String result) {
        if (result.contains("无内容返回") || result.startsWith("(空文件")) {
            return new Entry(false, ZeroKind.OVERLAP_READ);
        }
        String path = normalizePath(arg(call, "path"));
        if (path.isEmpty()) {
            return new Entry(true, null);
        }
        seenFiles.add(path);
        Matcher footer = READ_FOOTER.matcher(result);
        if (!footer.find()) {
            boolean freshFile = readFiles.add(path);
            return freshFile ? new Entry(true, null) : new Entry(false, ZeroKind.OVERLAP_READ);
        }
        int start = Integer.parseInt(footer.group(1));
        int end = Integer.parseInt(footer.group(2));
        if (start < 1 || end < start) {
            return new Entry(false, ZeroKind.OVERLAP_READ);
        }
        List<ReadGuard.LineRange> existing = readRangesByFile.getOrDefault(path, List.of());
        double overlap = overlapRatio(existing, start, end);
        readFiles.add(path);
        readRangesByFile.put(path, merge(existing, new ReadGuard.LineRange(start, end)));
        return overlap < OVERLAP_ZERO_GAIN_RATIO ? new Entry(true, null) : new Entry(false, ZeroKind.OVERLAP_READ);
    }

    private Entry classifySearch(String result) {
        if (result.startsWith("未找到匹配项:")) {
            return new Entry(false, ZeroKind.EMPTY_SEARCH);
        }
        boolean foundNew = false;
        boolean parsedAny = false;
        for (String line : result.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || "--".equals(trimmed) || trimmed.startsWith("…")) {
                continue;
            }
            String extracted = extractSearchPath(trimmed);
            if (extracted != null) {
                parsedAny = true;
                if (seenFiles.add(normalizePath(extracted))) {
                    foundNew = true;
                }
            }
        }
        if (!parsedAny) {
            return new Entry(true, null);
        }
        return foundNew ? new Entry(true, null) : new Entry(false, ZeroKind.REDUNDANT_HIT);
    }

    private Entry classifyGlob(String result) {
        if (result.startsWith("没有匹配的文件:")) {
            return new Entry(false, ZeroKind.EMPTY_SEARCH);
        }
        boolean foundNew = false;
        boolean parsedAny = false;
        for (String line : result.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("…") || trimmed.startsWith("（")) {
                continue;
            }
            parsedAny = true;
            if (seenFiles.add(normalizePath(trimmed))) {
                foundNew = true;
            }
        }
        if (!parsedAny) {
            return new Entry(true, null);
        }
        return foundNew ? new Entry(true, null) : new Entry(false, ZeroKind.REDUNDANT_HIT);
    }

    private String formatBreakdown() {
        EnumMap<ZeroKind, Integer> counts = new EnumMap<>(ZeroKind.class);
        for (Entry entry : window) {
            if (!entry.gained() && entry.zeroKind() != null) {
                counts.merge(entry.zeroKind(), 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        appendPart(parts, "空搜索", counts.getOrDefault(ZeroKind.EMPTY_SEARCH, 0));
        appendPart(parts, "重复/重叠读取",
            counts.getOrDefault(ZeroKind.DEDUP, 0) + counts.getOrDefault(ZeroKind.OVERLAP_READ, 0));
        appendPart(parts, "仅命中已知位置", counts.getOrDefault(ZeroKind.REDUNDANT_HIT, 0));
        appendPart(parts, "查询失败", counts.getOrDefault(ZeroKind.ERROR, 0));
        return String.join("、", parts);
    }

    private void recordFileObservation(ToolCall call) {
        String name = call.name();
        if (!ToolNames.READ_FILE.equals(name) && !CodeSymbolTool.NAME.equals(name)
            && !ToolNames.SEARCH_TEXT.equals(name)) {
            return;
        }
        String path = normalizePath(arg(call, "path"));
        // 宽目录检索不记作单文件反复查看；只统计指向具体文件（含扩展名）的观察。
        if (path.isEmpty() || (ToolNames.SEARCH_TEXT.equals(name) && !looksLikeFile(path))) {
            return;
        }
        fileObservationCounts.merge(path, 1, Integer::sum);
    }

    private String formatHotspots() {
        return fileObservationCounts.entrySet().stream().filter(e -> e.getValue() >= DISPLAY_HOTSPOT_MIN_OBS)
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).limit(MAX_DISPLAY_HOTSPOTS)
            .map(e -> shortName(e.getKey()) + " " + e.getValue() + "次").collect(Collectors.joining("、"));
    }

    private String formatReadFiles() {
        if (readFiles.isEmpty()) {
            return "";
        }
        List<String> names =
            readFiles.stream().limit(MAX_DISPLAY_READ_FILES).map(InvestigationTracker::shortName).distinct().toList();
        String joined = String.join("、", names);
        return readFiles.size() > MAX_DISPLAY_READ_FILES ? joined + " 等 " + readFiles.size() + " 个文件" : joined;
    }

    private static boolean looksLikeFile(String path) {
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = base.lastIndexOf('.');
        return dot > 0 && dot < base.length() - 1;
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void appendPart(List<String> parts, String label, int count) {
        if (count > 0) {
            parts.add(label + " " + count + " 次");
        }
    }

    private static String extractSearchPath(String line) {
        Matcher filesOnly = SEARCH_FILES_ONLY.matcher(line);
        if (filesOnly.matches()) {
            return filesOnly.group(1);
        }
        Matcher content = SEARCH_CONTENT_LINE.matcher(line);
        if (content.matches()) {
            return content.group(1);
        }
        return null;
    }

    private static String arg(ToolCall call, String key) {
        if (call.args() == null) {
            return "";
        }
        return Objects.toString(call.args().get(key), "").strip();
    }

    private static String normalizePath(String raw) {
        String normalized = raw == null ? "" : raw.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String stripExitCode(String check) {
        String value = Objects.toString(check, "");
        int arrow = value.lastIndexOf(" → exit ");
        return (arrow < 0 ? value : value.substring(0, arrow)).strip();
    }

    private static double overlapRatio(List<ReadGuard.LineRange> ranges, int start, int end) {
        if (ranges.isEmpty() || end < start) {
            return 0.0;
        }
        int covered = 0;
        for (ReadGuard.LineRange range : ranges) {
            if (range.end() < start) {
                continue;
            }
            if (range.start() > end) {
                break;
            }
            int lo = Math.max(start, range.start());
            int hi = Math.min(end, range.end());
            if (hi >= lo) {
                covered += hi - lo + 1;
            }
        }
        return covered / (double)(end - start + 1);
    }

    private static List<ReadGuard.LineRange> merge(List<ReadGuard.LineRange> existing, ReadGuard.LineRange added) {
        ArrayList<ReadGuard.LineRange> merged = new ArrayList<>(existing.size() + 1);
        int start = added.start();
        int end = added.end();
        boolean inserted = false;
        for (ReadGuard.LineRange range : existing) {
            if (range.end() + 1 < start) {
                merged.add(range);
            } else if (end + 1 < range.start()) {
                if (!inserted) {
                    merged.add(new ReadGuard.LineRange(start, end));
                    inserted = true;
                }
                merged.add(range);
            } else {
                start = Math.min(start, range.start());
                end = Math.max(end, range.end());
            }
        }
        if (!inserted) {
            merged.add(new ReadGuard.LineRange(start, end));
        }
        return List.copyOf(merged);
    }
}
