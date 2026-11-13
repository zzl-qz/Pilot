package com.pilot.core.loop;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 流式正文的复读检测：模型偶发退化时会把同一段话反复吐几十遍，界面上表现为刷屏，
 * 既占满上下文又没有任何信息。
 *
 * <p>两种形态都要拦：完全相同的行连续出现，以及同一句话被反复重写（每遍只多几个字，
 * 行级比较抓不到）。窗口有上限，检测成本不会随输出长度增长。
 */
final class StreamRepeatGuard {
    /** 连续相同非空行达到这个数就判定复读；正常正文几乎不会出现。 */
    static final int IDENTICAL_LINES = 8;
    /** 太短的行（括号、表格边框、分隔线）不参与判断，避免代码和表格误报。 */
    private static final int MIN_LINE_CHARS = 6;
    /** 只看最近这些字符，长回答不重复扫描全文。 */
    private static final int WINDOW_CHARS = 8_000;
    /** 新增内容不足这个长度时跳过检查，降低逐 token 的扫描开销；粒度越小越早止损。 */
    private static final int CHECK_EVERY_CHARS = 64;

    /** 连续多行是前一行的加长版，同样是复读的常见形态。 */
    private static final int GROWING_LINES = 5;
    private static final int MIN_GROWING_CHARS = 8;

    /** 完整长句反复出现；共同开头不足以判定两句话表达相同内容。 */
    private static final int MIN_SENTENCE_CHARS = 24;
    private static final int REPEATED_SENTENCES = 4;
    /** 只看最近这些句子，避免长文里隔很远的正常呼应被算成复读。 */
    private static final int SENTENCE_WINDOW = 16;
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？\\n]");
    private static final String MARKDOWN_MARKS = "*#-> 　";

    private final StringBuilder tail = new StringBuilder();
    private int sinceCheck;

    /**
     * 吃进一段增量文本。
     *
     * @return 命中的重复计数；未达到阈值返回 0
     */
    int accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return 0;
        }
        tail.append(delta);
        if (tail.length() > WINDOW_CHARS) {
            tail.delete(0, tail.length() - WINDOW_CHARS);
        }
        sinceCheck += delta.length();
        if (sinceCheck < CHECK_EVERY_CHARS && delta.indexOf('\n') < 0) {
            return 0;
        }
        sinceCheck = 0;
        int lines = repeatedLines();
        if (lines > 0) {
            return lines;
        }
        int growing = growingLines();
        return growing > 0 ? growing : repeatedSentences();
    }

    /**
     * 逐行扩写：后一行是前一行的加长版（复读时常见的列表形态，每行只比上一行多几个字）。
     * 这种行既不相等、开头也不足以按句比较，只能按「前缀包含」识别。
     */
    private int growingLines() {
        String[] lines = tail.toString().split("\n", -1);
        String longer = null;
        int run = 0;
        // 最后一段尚未遇到换行，后续增量可能让它成为另一条有效内容。
        for (int index = lines.length - 2; index >= 0; index--) {
            String line = lines[index].strip();
            if (line.length() < MIN_GROWING_CHARS) {
                continue;
            }
            if (longer == null) {
                longer = line;
                run = 1;
            } else if (extendsLine(line, longer)) {
                run++;
                longer = line;
            } else {
                break;
            }
            if (run >= GROWING_LINES) {
                return run;
            }
        }
        return 0;
    }

    /** 只识别完整保留上一行的逐行扩写；末尾不同的代码、数据和说明不能按相似度中止。 */
    private static boolean extendsLine(String shorter, String longer) {
        return longer.length() > shorter.length() && longer.startsWith(shorter);
    }

    private int repeatedLines() {
        String[] lines = tail.toString().split("\n", -1);
        String last = null;
        int run = 0;
        for (int index = lines.length - 2; index >= 0; index--) {
            String line = lines[index].strip();
            if (line.length() < MIN_LINE_CHARS) {
                continue;
            }
            if (last == null) {
                last = line;
                run = 1;
            } else if (line.equals(last)) {
                run++;
            } else {
                break;
            }
            if (run >= IDENTICAL_LINES) {
                return run;
            }
        }
        return 0;
    }

    /** 只统计完整相同的长句，避免把列举同主题的不同规则误判成复读。 */
    private int repeatedSentences() {
        String[] sentences = SENTENCE_END.split(tail.toString(), -1);
        Map<String, Integer> counts = new HashMap<String, Integer>();
        int observed = 0;
        // 跳过未完成的句子与空行；空白排版不应改变相同正文的判断。
        for (int index = sentences.length - 2; index >= 0 && observed < SENTENCE_WINDOW; index--) {
            String sentence = stripMarks(sentences[index]);
            if (sentence.isEmpty()) {
                continue;
            }
            observed++;
            if (sentence.length() < MIN_SENTENCE_CHARS) {
                continue;
            }
            int count = counts.merge(sentence, 1, Integer::sum);
            if (count >= REPEATED_SENTENCES) {
                return count;
            }
        }
        return 0;
    }

    private static String stripMarks(String value) {
        String text = value == null ? "" : value.strip();
        int start = 0;
        while (start < text.length() && MARKDOWN_MARKS.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        return text.substring(start);
    }
}
