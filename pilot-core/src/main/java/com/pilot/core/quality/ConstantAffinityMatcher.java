package com.pilot.core.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.quality.CodeAssetIndex.ConstantRef;
import com.pilot.core.util.FileNames;

/**
 * 常量与枚举推荐的语境亲和度匹配器。
 * 依次执行语言与模块方向隔离、外部协议边界保护、枚举化量纲校验与实体词根打分，杜绝跨领域乱引。
 */
final class ConstantAffinityMatcher {
    private static final int HIGH_AFFINITY_THRESHOLD = 45;
    private static final int AMBIGUOUS_MARGIN = 20;

    private static final Set<String> JVM_EXTS = Set.of("java", "kt", "kts");
    private static final Set<String> JS_EXTS = Set.of("ts", "tsx", "js", "jsx", "mjs", "cjs");
    private static final Set<String> SHARED_MODULE_HINTS =
        Set.of("core", "common", "commons", "shared", "api", "base", "sdk", "pkg", "lib", "util", "utils");

    private static final Set<String> GENERIC_NOISE_WORDS = Set.of("src", "main", "java", "kotlin", "ts", "tsx", "js",
        "jsx", "com", "org", "net", "io", "cn", "co", "dev", "pilot", "app", "apps", "demo", "example", "server", "web",
        "client", "frontend", "backend", "internal", "impl", "common", "commons", "shared", "util", "utils", "lib",
        "libs", "pkg", "core", "base", "api", "sdk", "hook", "hooks", "feature", "features", "module", "modules",
        "component", "components", "view", "views", "helper", "helpers", "support", "constant", "constants", "config",
        "configs", "enum", "enums", "model", "models", "entity", "dto", "vo", "bo", "po", "service", "services",
        "controller", "controllers", "manager", "handler", "processor", "provider", "factory", "repository", "store",
        "status", "state", "phase", "stage", "type", "types", "kind", "mode", "code", "codes", "result", "default",
        "max", "min", "limit", "limits", "value", "values", "item", "items", "info", "data");

    private static final Set<String> INFRA_OWNER_WORDS = Set.of("time", "date", "duration", "chrono", "http", "mime",
        "media", "charset", "encoding", "byte", "size", "math", "unit", "system", "protocol", "page", "pagination",
        "format", "formatter", "text", "texts", "string", "strings", "number", "numbers", "file", "path", "paths");

    private static final Set<String> EXTERNAL_BOUNDARY_HINTS = Set.of("webhook", "callback", "thirdparty", "external",
        "payload", "wire", "header", "headers", "sql", "select", "where", "insert", "json", "xml", "rawresponse");

    private static final Pattern WORD_SPLIT = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.]+);");

    private ConstantAffinityMatcher() {
    }

    /** 枚举化量纲词表：新增量纲无需堆叠分支。 */
    enum Dimension {
        TIME_MS(true, "ms", "millis", "millisecond", "milliseconds", "sleep", "backoff"),
        TIME_SEC(true, "sec", "secs", "second", "seconds", "minute", "minutes", "hour", "hours", "day", "days", "ttl"),
        TIME_GENERIC(true, "timeout", "expire", "expired", "expiry", "delay", "interval", "duration", "retention"),
        BYTE_SIZE(false, "byte", "bytes", "kb", "kib", "mb", "mib", "gb", "gib", "buffer", "chunk", "memory"),
        PAGE(false, "page", "pages", "pagesize", "perpage", "offset", "cursor"),
        RETRY(false, "retry", "retries", "attempt", "attempts"), PORT_HTTP(false, "port", "http", "statuscode"),
        COUNT_LENGTH(false, "length", "len", "char", "chars", "file", "files", "row", "rows", "line", "lines", "depth",
            "thread", "threads", "pool", "queue", "batch", "count"),
        NONE(false);

        private final boolean timeFamily;
        private final Set<String> keywords;

        Dimension(boolean timeFamily, String... words) {
            this.timeFamily = timeFamily;
            this.keywords = Set.of(words);
        }

        boolean clashesWith(Dimension other) {
            if (this == NONE || other == NONE || this == other) {
                return false;
            }
            return !((this == TIME_GENERIC && other.timeFamily) || (other == TIME_GENERIC && this.timeFamily));
        }

        static Dimension detect(Set<String> words) {
            for (Dimension dim : values()) {
                if (dim != NONE && hasAny(words, dim.keywords)) {
                    return dim;
                }
            }
            return NONE;
        }
    }

    record MatchContext(String literal, boolean numeric, String currentPath, String rawLine, Set<String> importedWords,
        Set<String> importedPaths) {

        static MatchContext of(String literal, boolean numeric, String currentPath, String rawLine,
            Set<String> importedWords, Set<String> importedPaths) {
            return new MatchContext(literal == null ? "" : literal.strip(), numeric,
                currentPath == null ? "" : currentPath.replace('\\', '/'), rawLine == null ? "" : rawLine,
                importedWords == null ? Set.of() : importedWords, importedPaths == null ? Set.of() : importedPaths);
        }
    }

    static String advise(List<ConstantRef> candidates, MatchContext ctx) {
        Set<String> lineWords = splitWords(ctx.rawLine());
        if (!ctx.numeric() && hasAny(lineWords, EXTERNAL_BOUNDARY_HINTS)) {
            return "该处疑似外部协议、请求头或持久化边界字面量；仅在与内部契约同源时才提炼协议常量，切勿强行绑定无关的内部业务枚举";
        }
        if (candidates == null || candidates.isEmpty()) {
            return defaultLocalAdvice(ctx.numeric());
        }

        Set<String> contextDomains = new LinkedHashSet<>(entityDomainWords(ctx.currentPath(), stem(ctx.currentPath())));
        contextDomains.addAll(domainWords(ctx.rawLine()));
        contextDomains.addAll(ctx.importedWords());

        Set<String> semanticWords = new LinkedHashSet<>(lineWords);
        semanticWords.addAll(splitWords(stem(ctx.currentPath())));
        Dimension lineDim = Dimension.detect(lineWords);

        List<Scored> eligible = new ArrayList<>();
        for (ConstantRef cand : candidates) {
            if (!cand.path().equals(ctx.currentPath()) && reachable(ctx.currentPath(), cand.path())) {
                int score = score(cand, ctx, contextDomains, semanticWords, lineDim);
                if (score > 0) {
                    eligible.add(new Scored(cand, score));
                }
            }
        }
        if (eligible.isEmpty()) {
            return defaultLocalAdvice(ctx.numeric());
        }
        eligible.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.ref().symbol()));
        Scored top = eligible.getFirst();
        if (top.score() >= HIGH_AFFINITY_THRESHOLD
            && (eligible.size() == 1 || top.score() - eligible.get(1).score() >= AMBIGUOUS_MARGIN)) {
            return "同链路已有可复用定义 " + top.ref().symbol() + "（" + top.ref().path()
                + "），请核对与当前上下文确属同一业务语义后再引用；若只是巧合同值请在本模块独立定义";
        }
        List<String> shown =
            eligible.stream().limit(3).map(s -> s.ref().symbol() + "（" + s.ref().path() + "）").distinct().toList();
        return shown.size() == 1 ? "仓库存在候选定义 " + shown.getFirst() + "；请先 read_file 核对是否同属一条业务链路，无关时请在本类就近定义常量，切勿跨领域强行耦合"
            : "仓库存在多个同名或同值定义：" + String.join("、", shown) + "；请结合业务语境核对归属，若均不属于当前概念请在本模块独立定义，切勿跨领域混用";
    }

    private static int score(ConstantRef cand, MatchContext ctx, Set<String> contextDomains, Set<String> semanticWords,
        Dimension lineDim) {
        int dot = cand.symbol().indexOf('.');
        String owner = dot < 0 ? cand.symbol() : cand.symbol().substring(0, dot);
        String member = dot < 0 ? cand.symbol() : cand.symbol().substring(dot + 1);
        Dimension candDim = Dimension.detect(splitWords(cand.path() + " " + cand.symbol()));
        if (lineDim.clashesWith(candDim)) {
            return -1;
        }

        boolean imported = ctx.importedWords().contains(owner.toLowerCase(Locale.ROOT))
            || ctx.importedPaths().stream().anyMatch(imp -> stripExt(cand.path()).endsWith(imp));
        boolean sameDir = parentDir(ctx.currentPath()).equals(parentDir(cand.path()));
        Set<String> candDomains = entityDomainWords(cand.path(), owner);
        int domainOverlap = countOverlap(candDomains, contextDomains);
        boolean infraNeutral = candDomains.isEmpty() || hasAny(splitWords(owner), INFRA_OWNER_WORDS);
        int semanticOverlap = countOverlap(splitWords(cand.symbol()), semanticWords);

        if (ctx.numeric()) {
            boolean dimAligned = lineDim != Dimension.NONE
                && (lineDim == candDim || (lineDim.timeFamily && candDim.timeFamily && !lineDim.clashesWith(candDim)));
            if (!imported && semanticOverlap == 0 && !dimAligned) {
                return -1;
            }
        }
        if (!imported && !sameDir && !candDomains.isEmpty() && !infraNeutral && domainOverlap == 0) {
            return -1;
        }

        int score = 10 + (imported ? 90 : 0) + (sameDir ? 55 : sharesParentPkg(ctx.currentPath(), cand.path()) ? 30 : 0)
            + domainOverlap * 40 + (infraNeutral && lineDim != Dimension.NONE ? 35 : 0);
        score +=
            ctx.numeric() ? Math.min(30, semanticOverlap * 15) : (hasAny(splitWords(member), semanticWords) ? 10 : 0);
        return score;
    }

    private static boolean reachable(String currentPath, String candidatePath) {
        String curExt = FileNames.extension(currentPath);
        String candExt = FileNames.extension(candidatePath);
        boolean sameLang = (JVM_EXTS.contains(curExt) && JVM_EXTS.contains(candExt))
            || (JS_EXTS.contains(curExt) && JS_EXTS.contains(candExt)) || curExt.equals(candExt);
        if (!sameLang) {
            return false;
        }
        String curMod = topModule(currentPath);
        String candMod = topModule(candidatePath);
        return curMod.isEmpty() || candMod.isEmpty() || curMod.equals(candMod)
            || hasAny(splitWords(candMod), SHARED_MODULE_HINTS);
    }

    static Set<String> extractImportedWords(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        text.lines().limit(80).map(String::strip)
            .filter(l -> l.startsWith("import ") || l.startsWith("from ") || l.startsWith("use "))
            .forEach(l -> words.addAll(splitWords(l)));
        return words;
    }

    static Set<String> extractImportedPaths(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        text.lines().limit(80).forEach(line -> {
            Matcher m = JAVA_IMPORT.matcher(line);
            if (m.find()) {
                paths.add(m.group(1).replace('.', '/'));
            }
        });
        return paths;
    }

    private static Set<String> entityDomainWords(String path, String owner) {
        String dir = parentDir(path);
        String leaf = dir.substring(dir.lastIndexOf('/') + 1);
        Set<String> words = domainWords(owner + " " + leaf);
        words.removeAll(INFRA_OWNER_WORDS);
        return words;
    }

    private static Set<String> domainWords(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : splitWords(text)) {
            if (w.length() >= 3 && !GENERIC_NOISE_WORDS.contains(w) && Dimension.detect(Set.of(w)) == Dimension.NONE) {
                out.add(w);
            }
        }
        return out;
    }

    static Set<String> splitWords(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String part : WORD_SPLIT.split(text)) {
            if (!part.isBlank()) {
                String w = part.toLowerCase(Locale.ROOT);
                words.add(w);
                if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
                    words.add(w.substring(0, w.length() - 1));
                }
            }
        }
        return words;
    }

    private static String defaultLocalAdvice(boolean numeric) {
        return numeric ? "若有业务或配置阈值含义，请在本类或当前模块内就近提炼具名常量（切勿借用无关业务域碰巧同值的常量）"
            : "请结合当前业务语境核对：同属一条业务链路时复用对应枚举/常量，否则在当前模块独立定义，切勿跨领域混用同名状态";
    }

    private static String topModule(String path) {
        int slash = path.indexOf('/');
        String first = slash <= 0 ? "" : path.substring(0, slash);
        return "src".equalsIgnoreCase(first) ? "" : first.toLowerCase(Locale.ROOT);
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean sharesParentPkg(String p1, String p2) {
        String gp1 = parentDir(parentDir(p1));
        return !gp1.isEmpty() && gp1.equals(parentDir(parentDir(p2))) && !gp1.endsWith("/java")
            && !gp1.endsWith("/src");
    }

    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return stripExt(name);
    }

    private static String stripExt(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }

    private static int countOverlap(Set<String> left, Set<String> right) {
        int n = 0;
        for (String item : left) {
            if (right.contains(item)) {
                n++;
            }
        }
        return n;
    }

    private static boolean hasAny(Set<String> source, Set<String> targets) {
        for (String item : source) {
            if (targets.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private record Scored(ConstantRef ref, int score) {
    }
}
