package com.pilot.core.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    public static final int TINY_READ_LINES = 25;
    /** 同一未变文件非重叠小窗微读累计达到该次数时附带软提示。 */
    public static final int TINY_READ_HINT_AT = 4;

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
        int covered = coveredLines(state.ranges(), resolvedStart, resolvedEnd);
        double ratio = covered / (double)(resolvedEnd - resolvedStart + 1);
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
            List<LineRange> merged = merge(current.ranges(), added);
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

    private static int coveredLines(List<LineRange> ranges, int start, int end) {
        int total = 0;
        for (LineRange range : ranges) {
            if (range.end() < start) {
                continue;
            }
            if (range.start() > end) {
                break;
            }
            int lo = Math.max(start, range.start());
            int hi = Math.min(end, range.end());
            if (hi >= lo) {
                total += hi - lo + 1;
            }
        }
        return total;
    }

    private static List<LineRange> merge(List<LineRange> existing, LineRange added) {
        ArrayList<LineRange> result = new ArrayList<>(existing.size() + 1);
        int start = added.start();
        int end = added.end();
        boolean inserted = false;
        for (LineRange range : existing) {
            if (range.end() + 1 < start) {
                result.add(range);
            } else if (end + 1 < range.start()) {
                if (!inserted) {
                    result.add(new LineRange(start, end));
                    inserted = true;
                }
                result.add(range);
            } else {
                start = Math.min(start, range.start());
                end = Math.max(end, range.end());
            }
        }
        if (!inserted) {
            result.add(new LineRange(start, end));
        }
        return List.copyOf(result);
    }

    private static FileStamp stamp(Path file) {
        try {
            BasicFileAttributes attributes =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new FileStamp(attributes.lastModifiedTime().toMillis(), attributes.size(), digest(file));
        } catch (IOException e) {
            return new FileStamp(-1, -1, "");
        }
    }

    /** 同样长度的替换不会改变大小，必须比较内容。 */
    private static String digest(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
