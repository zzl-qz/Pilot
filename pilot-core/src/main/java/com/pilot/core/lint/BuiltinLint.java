package com.pilot.core.lint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < REPEAT_LIMIT || !addedText(added).contains(entry.getKey())) {
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
        Map<String, Integer> existing = new LinkedHashMap<>();
        for (String line : lines(before)) {
            existing.merge(line.strip(), 1, Integer::sum);
        }
        List<String> added = new ArrayList<>();
        for (String line : lines(after)) {
            String key = line.strip();
            if (key.isEmpty()) {
                continue;
            }
            int left = existing.getOrDefault(key, 0);
            if (left > 0) {
                existing.put(key, left - 1);
            } else {
                added.add(key);
            }
        }
        return added;
    }

    static Set<Integer> addedLineNumbers(String before, String after) {
        Map<String, Integer> existing = new LinkedHashMap<>();
        for (String line : lines(before)) {
            existing.merge(line.strip(), 1, Integer::sum);
        }
        Set<Integer> numbers = new LinkedHashSet<>();
        int number = 0;
        for (String line : lines(after)) {
            number++;
            String key = line.strip();
            if (key.isEmpty()) {
                continue;
            }
            int left = existing.getOrDefault(key, 0);
            if (left > 0) {
                existing.put(key, left - 1);
            } else {
                numbers.add(number);
            }
        }
        return numbers;
    }

    private static boolean isComment(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")
            || trimmed.startsWith("/*");
    }

    private static String addedText(List<String> added) {
        return String.join("\n", added);
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\\R"));
    }
}
