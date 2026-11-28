package com.pilot.core.lint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浅检查：仓库没有自己的 lint 时，只看本次新行里的裸数字和重复字符串。
 * 仓库已经有 lint 时不使用。不理解业务，也不做跨文件克隆。
 */
final class BuiltinLint {
    private static final Set<String> ALLOWED_NUMBERS = Set.of("0", "1", "-1", "2", "10", "100");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])");
    private static final Pattern STRING = Pattern.compile("\"[^\"\\\\]{5,80}\"|'[^'\\\\]{5,80}'");
    private static final int REPEAT_LIMIT = 3;

    private BuiltinLint() {
    }

    static List<String> scan(String path, String before, String after) {
        List<String> added = addedLines(before, after);
        if (added.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : added) {
            if (isComment(line)) {
                continue;
            }
            String code = line.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])*'", "''");
            Matcher numbers = NUMBER.matcher(code);
            if (numbers.find() && !ALLOWED_NUMBERS.contains(numbers.group())) {
                out.add(path + ": 裸数字 " + numbers.group() + "。业务阈值建议写成有名字的常量；一次性的值可以保留");
                if (out.size() >= 8) {
                    return List.copyOf(out);
                }
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher strings = STRING.matcher(after == null ? "" : after);
        while (strings.find()) {
            counts.merge(strings.group(), 1, Integer::sum);
        }
        String addedBlock = String.join("\n", added);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < REPEAT_LIMIT || !addedBlock.contains(entry.getKey())) {
                continue;
            }
            out.add(path + ": 字符串 " + entry.getKey() + " 出现 " + entry.getValue() + " 次。同一概念建议提成常量");
            if (out.size() >= 8) {
                break;
            }
        }
        return List.copyOf(out);
    }

    static List<String> addedLines(String before, String after) {
        List<String> added = new ArrayList<>();
        diffAdded(before, after, key -> !key.isEmpty(), (number, key) -> added.add(key));
        return added;
    }

    static Set<Integer> addedLineNumbers(String before, String after) {
        Set<Integer> numbers = new LinkedHashSet<>();
        diffAdded(before, after, key -> !key.isEmpty(), (number, key) -> numbers.add(number));
        return numbers;
    }

    /** 按多重集差集遍历本次新增行（1-based 行号 + 去首尾空白文本），过滤条件由调用方决定。 */
    static void diffAdded(String before, String after, Predicate<String> include, BiConsumer<Integer, String> onAdded) {
        Map<String, Integer> existing = new LinkedHashMap<>();
        for (String line : lines(before)) {
            String key = line.strip();
            if (include.test(key)) {
                existing.merge(key, 1, Integer::sum);
            }
        }
        int number = 0;
        for (String line : lines(after)) {
            number++;
            String key = line.strip();
            if (!include.test(key)) {
                continue;
            }
            int left = existing.getOrDefault(key, 0);
            if (left > 0) {
                existing.put(key, left - 1);
            } else {
                onAdded.accept(number, key);
            }
        }
    }

    private static boolean isComment(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")
            || trimmed.startsWith("/*");
    }

    static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\\R"));
    }
}
