package com.pilot.core.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.pilot.core.util.Sha256;

/**
 * {@link ReadGuard} 的进程内实现：一次 Agent 运行一份。
 *
 * <p>读过的文件记下修改时间、大小和内容指纹，外部改过就拒绝盲写；
 * 同时按规范化路径与内容指纹维护已交付闭区间表，只拦截高重叠重复读取，不误伤大文件顺次分页。
 */
public final class ReadTracker implements ReadGuard {

    private record FileStamp(long modifiedMillis, long sizeBytes, String digest) {
    }

    private record DeliveredState(String stamp, int totalLines, List<LineRange> ranges, int tinyReads) {
    }

    /** 同一未变文件请求区间覆盖率达到该阈值即视为重复读取。 */
    public static final double OVERLAP_DEDUP_RATIO = 0.80;
    /** 单次读取不超过该行数视为小窗微读，用于给出温和的增大 line_count 建议。 */
    public static final int TINY_READ_LINES = 60;
    /** 同一未变文件非重叠小窗微读累计达到该次数时附带软提示。 */
    public static final int TINY_READ_HINT_AT = 3;

    private final ConcurrentMap<Path, FileStamp> observed = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, DeliveredState> deliveredViews = new ConcurrentHashMap<>();
    private final AtomicInteger revision = new AtomicInteger();
    private final AtomicInteger viewEpoch = new AtomicInteger();

    @Override
    public void observed(Path file) {
        if (file == null) {
            return;
        }
        Path key = normalize(file);
        FileStamp next = stamp(key);
        FileStamp previous = observed.put(key, next);
        if (previous != null && !previous.equals(next)) {
            deliveredViews.remove(key);
        }
        if (previous == null || !previous.equals(next)) {
            revision.incrementAndGet();
        }
    }

    @Override
    public ReadCoverage checkDelivered(Path file, String stamp, int startLine, int lineCount) {
        if (file == null || stamp == null || stamp.isBlank() || lineCount < 1) {
            return ReadCoverage.NONE;
        }
        DeliveredState state = deliveredViews.get(normalize(file));
        if (state == null || !stamp.equals(state.stamp()) || state.ranges().isEmpty() || state.totalLines() < 1) {
            return ReadCoverage.NONE;
        }
        int total = state.totalLines();
        long requestedStart = startLine < 0 ? (long)total + startLine + 1 : startLine;
        if (requestedStart > total) {
            return new ReadCoverage(0.0, 0, 0, state.ranges(), state.tinyReads());
        }
        int resolvedStart = (int)Math.max(1L, requestedStart);
        int resolvedEnd = (int)Math.min(total, (long)resolvedStart + lineCount - 1);
        if (resolvedEnd < resolvedStart) {
            return new ReadCoverage(0.0, 0, 0, state.ranges(), state.tinyReads());
        }
        double ratio = LineRange.overlapRatio(state.ranges(), resolvedStart, resolvedEnd);
        return new ReadCoverage(ratio, resolvedStart, resolvedEnd, state.ranges(), state.tinyReads());
    }

    @Override
    public void recordDelivered(Path file, String stamp, int startLine, int endLine, int totalLines) {
        if (file == null || stamp == null || stamp.isBlank() || startLine < 1 || endLine < startLine
            || totalLines < 1) {
            return;
        }
        LineRange added = new LineRange(startLine, endLine);
        boolean tiny = added.length() <= TINY_READ_LINES && endLine < totalLines;
        deliveredViews.compute(normalize(file), (ignored, current) -> {
            if (current == null || !stamp.equals(current.stamp())) {
                return new DeliveredState(stamp, totalLines, List.of(added), tiny ? 1 : 0);
            }
            List<LineRange> merged = LineRange.merge(current.ranges(), added);
            int nextTiny = tiny ? current.tinyReads() + 1 : current.tinyReads();
            return new DeliveredState(stamp, totalLines, merged, nextTiny);
        });
    }

    @Override
    public List<LineRange> deliveredRanges(Path file, String stamp) {
        if (file == null || stamp == null || stamp.isBlank()) {
            return List.of();
        }
        DeliveredState state = deliveredViews.get(normalize(file));
        if (state == null || !stamp.equals(state.stamp())) {
            return List.of();
        }
        return state.ranges();
    }

    @Override
    public boolean isFullyDelivered(Path file, String stamp) {
        if (file == null || stamp == null || stamp.isBlank()) {
            return false;
        }
        DeliveredState state = deliveredViews.get(normalize(file));
        if (state == null || !stamp.equals(state.stamp()) || state.totalLines() < 1 || state.ranges().size() != 1) {
            return false;
        }
        LineRange only = state.ranges().getFirst();
        return only.start() == 1 && only.end() >= state.totalLines();
    }

    @Override
    public void invalidateDeliveredViews() {
        deliveredViews.clear();
        viewEpoch.incrementAndGet();
    }

    @Override
    public void invalidateDeliveredView(Path file) {
        if (file == null) {
            return;
        }
        if (deliveredViews.remove(normalize(file)) != null) {
            viewEpoch.incrementAndGet();
        }
    }

    @Override
    public int viewEpoch() {
        return viewEpoch.get();
    }

    @Override
    public String observationStamp(Path file) {
        if (file == null) {
            return "";
        }
        FileStamp now = stamp(normalize(file));
        if (now.modifiedMillis() < 0) {
            return "";
        }
        return now.modifiedMillis() + ":" + now.sizeBytes() + ":" + now.digest();
    }

    @Override
    public int revision() {
        return revision.get();
    }

    @Override
    public boolean isObserved(Path file) {
        return file == null || observed.containsKey(normalize(file));
    }

    @Override
    public boolean isFresh(Path file) {
        if (file == null) {
            return true;
        }
        Path key = normalize(file);
        FileStamp seen = observed.get(key);
        return seen == null || seen.equals(stamp(key));
    }

    private static Path normalize(Path file) {
        return file.toAbsolutePath().normalize();
    }

    private static FileStamp stamp(Path file) {
        try {
            BasicFileAttributes attributes =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new FileStamp(attributes.lastModifiedTime().toMillis(), attributes.size(), Sha256.hex(file));
        } catch (IOException e) {
            return new FileStamp(-1, -1, "");
        }
    }
}
