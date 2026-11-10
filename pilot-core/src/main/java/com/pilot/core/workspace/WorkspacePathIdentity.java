package com.pilot.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 已有文件与虚拟新文件共用的路径身份；以目标文件系统确认别名，不按操作系统猜测。 */
public final class WorkspacePathIdentity {
    private final PathValidator paths;
    private final Map<String, DirectoryNames> directories = new HashMap<>();

    public WorkspacePathIdentity(Path workspace) {
        paths = new PathValidator(workspace);
    }

    public Path root() {
        return paths.root();
    }

    public synchronized String key(String path) {
        Path relative = paths.root().relativize(paths.resolve(path));
        Path directory = paths.root();
        Path probeDirectory = directory;
        StringBuilder key = new StringBuilder();
        for (Path part : relative) {
            if (Files.isDirectory(directory)) {
                probeDirectory = directory;
            }
            String name = part.toString();
            DirectoryNames names = directories.computeIfAbsent(key.toString(), ignored -> new DirectoryNames());
            String canonical = names.canonical(probeDirectory, name);
            if (!key.isEmpty()) {
                key.append('/');
            }
            key.append(canonical);
            // 新目录继承最近现存父目录的规则；现存挂载点/目录则在自身目录内确认。
            directory = directory.resolve(name);
        }
        return key.toString();
    }

    private static final class DirectoryNames {
        final Map<String, String> aliases = new HashMap<>();
        final Map<String, List<String>> candidates = new HashMap<>();

        String canonical(Path directory, String name) {
            String known = aliases.get(name);
            if (known != null) {
                return known;
            }
            List<String> bucket = candidates.computeIfAbsent(bucket(name), ignored -> new ArrayList<>());
            String canonical = bucket.isEmpty() ? name : probeAlias(directory, name, bucket);
            if (canonical.equals(name)) {
                bucket.add(name);
            }
            aliases.put(name, canonical);
            return canonical;
        }
    }

    private static String probeAlias(Path directory, String name, List<String> candidates) {
        // 只对疑似别名探测；探针在独占临时子目录中，文件名长度、Unicode 与大小写均交由文件系统判断。
        try {
            Path probe = Files.createTempDirectory(directory, ".pilot-path-identity-");
            Path file = probe.resolve(name);
            try {
                Files.createFile(file);
                for (String candidate : candidates) {
                    if (Files.exists(probe.resolve(candidate), LinkOption.NOFOLLOW_LINKS)) {
                        return candidate;
                    }
                }
                return name;
            } finally {
                Files.deleteIfExists(file);
                Files.delete(probe);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法确认目标目录的路径别名规则：" + directory, e);
        }
    }

    private static String bucket(String name) {
        // 宽松分组只缩小比较范围，不据此合并：例如 Linux 的大小写、重音和兼容字符仍须彼此独立。
        String folded =
            Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        folded.codePoints().forEach(code -> {
            int type = Character.getType(code);
            if (type != Character.FORMAT && type != Character.NON_SPACING_MARK
                && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                result.appendCodePoint(code);
            }
        });
        return result.toString().replaceAll("[. ]+$", "");
    }
}
