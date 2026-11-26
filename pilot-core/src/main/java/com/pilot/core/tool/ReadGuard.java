package com.pilot.core.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 「先读后改」与行区间视图的运行级记录：read_file 成功后登记路径与已交付行区间，
 * edit_file 执行前校验——本运行没读过或被外部改过的文件直接拒绝并返回补救指引。
 *
 * <p>豁免规则与 dsh 的 fs-observation-policy 一致：本运行刚创建或刚编辑过的文件视为已读
 * （create_file / edit_file 成功后同样登记），连续编辑不会被误伤。
 *
 * <p>已交付行区间用于拦截同一未变文件的高重叠重复读取，同时放行大文件的非重叠顺次分页；
 * 当上下文压缩或裁剪丢弃旧读取结果时，{@link #invalidateDeliveredViews()} 仅清视图、不破坏编辑安全记录。
 */
public interface ReadGuard {

    /** 1-based 闭区间 [start, end]。 */
    record LineRange(int start, int end) {
        public LineRange {
            if (start < 1 || end < start) {
                throw new IllegalArgumentException("invalid line range: " + start + ".." + end);
            }
        }

        public int length() {
            return end - start + 1;
        }

        public String format() {
            return start == end ? Integer.toString(start) : start + "-" + end;
        }

        public static String formatAll(List<LineRange> ranges) {
            if (ranges == null || ranges.isEmpty()) {
                return "";
            }
            return ranges.stream().map(LineRange::format).collect(Collectors.joining(", "));
        }
    }

    /**
     * 某次读取请求在当前文件指纹下的已交付覆盖情况。
     *
     * @param overlapRatio   本次请求行段已被已交付区间覆盖的比例（0.0–1.0）
     * @param resolvedStart  结合已知总行数折算后的起始行号（未知时为 0）
     * @param resolvedEnd    结合已知总行数折算后的结束行号（未知时为 0）
     * @param delivered      当前指纹下已交付的合并行区间列表
     * @param tinyReadCount  当前指纹下已发生的非重叠小窗微读次数
     */
    record ReadCoverage(double overlapRatio, int resolvedStart, int resolvedEnd, List<LineRange> delivered,
        int tinyReadCount) {
        public static final ReadCoverage NONE = new ReadCoverage(0.0, 0, 0, List.of(), 0);

        public ReadCoverage {
            delivered = delivered == null ? List.of() : List.copyOf(delivered);
        }
    }

    /** 记录一个已被本运行观察过（读取 / 创建 / 编辑）的文件。 */
    void observed(Path file);

    /** 该文件是否已在本运行观察过。 */
    boolean isObserved(Path file);

    /**
     * 文件此刻的内容指纹。读不到文件时返回空字符串。和上次读到的指纹相同就可以复用已交付区间。
     */
    default String observationStamp(Path file) {
        return "";
    }

    /** 工作区内容相对命令缓存有没有变过。读过新文件或写入后递增。 */
    default int revision() {
        return 0;
    }

    /**
     * 兼容保留方法；不再以整文件 3 次硬墙拦截大文件正常分页，请改用 {@link #checkDelivered}。
     */
    default boolean deliverRead(Path file) {
        return true;
    }

    /**
     * 检查同一未变文件的读取请求是否已被已交付行区间高比例覆盖。
     *
     * @param file      目标文件路径
     * @param stamp     实时内容指纹
     * @param startLine 请求的起始行（支持负数从末尾倒数）
     * @param lineCount 请求的最大行数
     */
    default ReadCoverage checkDelivered(Path file, String stamp, int startLine, int lineCount) {
        return ReadCoverage.NONE;
    }

    /** 登记本次实际向模型交付的行区间，自动合并重叠或相邻闭区间。 */
    default void recordDelivered(Path file, String stamp, int startLine, int endLine, int totalLines) {
    }

    /** 当前指纹下已交付的合并行区间；指纹不匹配或已失效时返回空列表。 */
    default List<LineRange> deliveredRanges(Path file, String stamp) {
        return List.of();
    }

    /** 当前指纹下该文件的全部行（1..totalLines）是否均已交付且未因压缩失效。 */
    default boolean isFullyDelivered(Path file, String stamp) {
        return false;
    }

    /** 上下文裁剪或摘要压缩后清空已交付读取视图，允许模型重新读取被压掉的文件；保留 {@link #isObserved} 与 {@link #isFresh}。 */
    default void invalidateDeliveredViews() {
    }

    /** 清空单个文件的已交付读取视图（如编辑/补丁匹配失败后允许模型立即重读原文核对）；保留 {@link #isObserved} 与 {@link #isFresh}。 */
    default void invalidateDeliveredView(Path file) {
    }

    /** 视图失效纪元：每次 {@link #invalidateDeliveredViews()} 调用时递增。 */
    default int viewEpoch() {
        return 0;
    }

    /** 已经写出新内容时的通知钩子。 */
    default void noteWrite() {
    }

    /**
     * 观察之后文件有没有被别的进程改过。默认视为没变，测试用的全放行实现不用记版本。
     */
    default boolean isFresh(Path file) {
        return true;
    }

    /** 全放行：测试与未装配场景的默认值。 */
    static ReadGuard allowAll() {
        return new ReadGuard() {
            @Override
            public void observed(Path file) {
            }

            @Override
            public boolean isObserved(Path file) {
                return true;
            }
        };
    }
}
