package com.pilot.core.tool.code;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.util.Texts;
import com.pilot.core.workspace.PathValidator;

/**
 * 纯本地、跨平台的文件结构大纲提取器：不依赖外部编译器/LSP，按扩展名抽取类型级与方法/函数级声明及精确行号，
 * 让模型读大文件前先 1 步定位目标方法行号。
 */
public final class FileOutline {
    public static final int MAX_SYMBOLS = 120;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_SIGNATURE_CHARS = 120;

    private static final Set<String> CONTROL_KEYWORDS = Set.of("if", "else", "for", "while", "switch", "case", "catch",
        "try", "finally", "synchronized", "return", "throw", "new", "super", "this", "assert", "yield", "do", "await",
        "typeof", "delete", "void", "constructor", "import", "export", "from", "package");

    private static final Pattern JAVA_TYPE = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:@?interface|class|record|enum)\\s+([A-Za-z_][\\w$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native|strictfp)\\s+)*(?:<[^>{};]+>\\s+)?(?:[\\w$.<>\\[\\], ?@]+\\s+)?([A-Za-z_][\\w$]*)\\s*\\(([^;{}]*?)\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?(?:\\{|;)");
    private static final Pattern KT_DECL = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|internal|open|final|abstract|sealed|data|value|enum|inner|override|suspend|inline|operator|infix|tailrec|external|companion)\\s+)*(class|interface|object|fun|typealias)\\s+(?:<[^>]+>\\s+)?([A-Za-z_][\\w$.<>]*)");
    private static final Pattern TS_TYPE = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:declare\\s+)?(?:abstract\\s+)?(class|interface|enum|type|namespace)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern TS_FUNCTION = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:declare\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*(?:<[^>]+>)?\\s*\\(");
    private static final Pattern TS_ARROW = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:<[^>]+>\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=>");
    private static final Pattern TS_METHOD = Pattern.compile(
        "^\\s{2,}(?:(?:public|protected|private|static|readonly|abstract|override|async|get|set)\\s+)*([A-Za-z_$#][\\w$]*)\\s*(?:<[^>]+>)?\\s*\\([^;]*\\)\\s*(?::\\s*[^{;=]+)?\\{");
    private static final Pattern PYTHON_DECL = Pattern.compile("^(\\s*)(?:async\\s+)?(class|def)\\s+([A-Za-z_]\\w*)");
    private static final Pattern GO_TYPE = Pattern.compile("^\\s*type\\s+([A-Za-z_]\\w*)\\s+(struct|interface|\\S+)");
    private static final Pattern GO_FUNC = Pattern.compile("^\\s*func\\s+(?:\\(([^)]+)\\)\\s+)?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern RUST_TYPE = Pattern
        .compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:unsafe\\s+)?(struct|enum|trait|impl|mod|type)\\s+([^{;]+)");
    private static final Pattern RUST_FN = Pattern
        .compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:const\\s+)?fn\\s+([A-Za-z_]\\w*)");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,3})\\s+(.+?)\\s*#*$");
    private static final Pattern GENERIC_DECL = Pattern.compile(
        "^\\s*(?:public\\s+|private\\s+|protected\\s+|export\\s+)?(class|interface|enum|struct|trait|func|function|def|fn|type)\\s+([A-Za-z_][\\w$]*)");

    private final PathValidator paths;

    public FileOutline(PathValidator paths) {
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
    }

    public String outline(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "ERROR: 缺少参数 path";
        }
        Path file;
        try {
            file = paths.resolve(rawPath);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.exists(file)) {
            return "ERROR: 文件不存在: " + rawPath;
        }
        if (!Files.isRegularFile(file)) {
            return "ERROR: 不是普通文件: " + rawPath;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return "ERROR: 文件超过 1 MB，无法提取大纲，请改用 search_text 或 read_file: " + rawPath;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (Texts.isBinary(bytes)) {
                return "ERROR: 二进制文件，无法提取大纲: " + rawPath;
            }
            String display = paths.display(file);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
            List<Symbol> symbols = extract(extension(display), lines);
            if (symbols.isEmpty()) {
                return "未在 " + display + " 中提取到结构符号（文件共 " + lines.length + " 行），可直接用 read_file 或 search_text。";
            }
            boolean truncated = symbols.size() > MAX_SYMBOLS;
            List<Symbol> shown = truncated ? symbols.subList(0, MAX_SYMBOLS) : symbols;
            StringBuilder out = new StringBuilder();
            out.append(display).append("（共 ").append(lines.length).append(" 行，提取 ").append(symbols.size())
                .append(" 个符号）");
            for (Symbol symbol : shown) {
                out.append('\n');
                if (symbol.nested()) {
                    out.append("  ");
                }
                out.append('L').append(symbol.line()).append(": ").append(symbol.label());
            }
            if (truncated) {
                out.append("\n… 仅显示前 ").append(MAX_SYMBOLS).append(" 个符号（其余 ").append(symbols.size() - MAX_SYMBOLS)
                    .append(" 个已省略）");
            }
            return out.toString();
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        }
    }

    private static List<Symbol> extract(String ext, String[] lines) {
        return switch (ext) {
            case "java" -> extractJava(lines);
            case "kt", "kts" -> extractKotlin(lines);
            case "ts", "tsx", "js", "jsx", "mjs", "cjs" -> extractTypeScript(lines);
            case "py" -> extractPython(lines);
            case "go" -> extractGo(lines);
            case "rs" -> extractRust(lines);
            case "md", "markdown" -> extractMarkdown(lines);
            default -> extractGeneric(lines);
        };
    }

    private static List<Symbol> extractJava(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (isJavaIgnoredLine(trimmed)) {
                continue;
            }
            if (JAVA_TYPE.matcher(raw).find()) {
                symbols.add(new Symbol(i + 1, clipSignature(trimmed), leadingSpaces(raw) >= 4));
                continue;
            }
            Symbol method = matchJavaMethod(lines, i, raw, trimmed);
            if (method != null) {
                symbols.add(method);
            }
        }
        return symbols;
    }

    private static boolean isJavaIgnoredLine(String trimmed) {
        return trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("@")
            || trimmed.startsWith("import ") || trimmed.startsWith("package ");
    }

    private static Symbol matchJavaMethod(String[] lines, int index, String raw, String trimmed) {
        // 方法签名里不会在 '(' 前出现 '='，排除字段初始化与 lambda 赋值
        int paren = trimmed.indexOf('(');
        int eq = trimmed.indexOf('=');
        if (paren < 0 || (eq >= 0 && eq < paren)) {
            return null;
        }
        String candidate = joinJavaSignature(lines, index, raw, trimmed);
        Matcher methodMatch = JAVA_METHOD.matcher(candidate);
        if (!methodMatch.find()) {
            return null;
        }
        String name = methodMatch.group(1);
        if (CONTROL_KEYWORDS.contains(name)) {
            return null;
        }
        // 排除普通语句调用：方法声明前必须有返回类型、修饰符，或是大写开头的构造器
        String beforeName = trimmed.substring(0, trimmed.indexOf(name)).strip();
        if ((beforeName.isEmpty() && !Character.isUpperCase(name.charAt(0))) || beforeName.endsWith(".")
            || beforeName.endsWith("->")) {
            return null;
        }
        return new Symbol(index + 1, clipSignature(trimTrailingBrace(candidate.strip())), true);
    }

    private static String joinJavaSignature(String[] lines, int index, String raw, String trimmed) {
        if (trimmed.endsWith("{") || trimmed.endsWith(";")) {
            return raw;
        }
        // 多行方法签名把后续最多 2 行拼在一起识别 '{' 或 ';'
        StringBuilder joined = new StringBuilder(raw);
        for (int next = index + 1; next < Math.min(lines.length, index + 3); next++) {
            joined.append(' ').append(lines[next].strip());
            if (lines[next].contains("{") || lines[next].contains(";")) {
                break;
            }
        }
        return joined.toString();
    }

    private static List<Symbol> extractKotlin(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            Matcher matcher = KT_DECL.matcher(raw);
            if (matcher.find()) {
                boolean nested = "fun".equals(matcher.group(1)) && leadingSpaces(raw) >= 2;
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(raw.strip())), nested));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractTypeScript(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (TS_TYPE.matcher(raw).find()) {
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(trimmed)), false));
                continue;
            }
            if (TS_FUNCTION.matcher(raw).find() || TS_ARROW.matcher(raw).find()) {
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(trimmed)), leadingSpaces(raw) >= 2));
                continue;
            }
            Matcher method = TS_METHOD.matcher(raw);
            if (method.find()) {
                String name = method.group(1);
                if (!CONTROL_KEYWORDS.contains(name)) {
                    symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(trimmed)), true));
                }
            }
        }
        return symbols;
    }

    private static List<Symbol> extractPython(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = PYTHON_DECL.matcher(lines[i]);
            if (matcher.find()) {
                boolean nested = !matcher.group(1).isEmpty();
                symbols.add(new Symbol(i + 1, clipSignature(lines[i].strip()), nested));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractGo(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (GO_TYPE.matcher(raw).find()) {
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(raw.strip())), false));
                continue;
            }
            Matcher fn = GO_FUNC.matcher(raw);
            if (fn.find()) {
                boolean method = fn.group(1) != null;
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(raw.strip())), method));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractRust(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (RUST_TYPE.matcher(raw).find()) {
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(raw.strip())), false));
                continue;
            }
            if (RUST_FN.matcher(raw).find()) {
                symbols.add(new Symbol(i + 1, clipSignature(trimTrailingBrace(raw.strip())), leadingSpaces(raw) >= 2));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractMarkdown(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher heading = MD_HEADING.matcher(trimmed);
            if (heading.find()) {
                symbols
                    .add(new Symbol(i + 1, heading.group(1) + " " + heading.group(2), heading.group(1).length() > 1));
            }
        }
        return symbols;
    }

    private static List<Symbol> extractGeneric(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = GENERIC_DECL.matcher(lines[i]);
            if (matcher.find()) {
                symbols.add(new Symbol(i + 1, matcher.group(1) + " " + matcher.group(2), leadingSpaces(lines[i]) >= 2));
            }
        }
        return symbols;
    }

    private static String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot >= path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length()) {
            char ch = line.charAt(count);
            if (ch == ' ') {
                count++;
            } else if (ch == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }

    private static String trimTrailingBrace(String text) {
        String result = text;
        if (result.endsWith("{")) {
            result = result.substring(0, result.length() - 1).stripTrailing();
        }
        return result;
    }

    private static String clipSignature(String text) {
        String singleLine = text.replaceAll("\\s+", " ").strip();
        if (singleLine.length() <= MAX_SIGNATURE_CHARS) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_SIGNATURE_CHARS) + "…";
    }

    private record Symbol(int line, String label, boolean nested) {
    }
}
