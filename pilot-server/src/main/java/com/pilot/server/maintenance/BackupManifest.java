package com.pilot.server.maintenance;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.pilot.core.util.FileTrees;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 备份发布、核验和恢复共用的清单规则。 */
final class BackupManifest {
    static final String FILE = "manifest.json";
    static final String COMPLETE = "COMPLETE";
    static final String DATABASE = "pilot.db";
    static final String VERSION = "4";
    private static final Pattern NONPORTABLE = Pattern.compile("[<>:\"\\\\|?*\\p{Cntrl}]");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?");

    record Check(int files, List<String> failures) {
        boolean valid() {
            return failures.isEmpty();
        }
    }

    private BackupManifest() {
    }

    static boolean published(Path directory, ObjectMapper mapper) {
        try {
            readPublished(directory, mapper);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static JsonNode readPublished(Path directory, ObjectMapper mapper) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            || directory.getFileName().toString().startsWith(".")) {
            throw new IOException("备份目录尚未发布");
        }
        JsonNode manifest = read(directory, mapper);
        if (VERSION.equals(version(manifest))
            && !directory.getFileName().toString().equals(manifest.path("id").asText())) {
            throw new IOException("备份目录与清单标识不匹配");
        }
        if (!"2".equals(version(manifest))) {
            Path marker = directory.resolve(COMPLETE);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("备份缺少完成标记");
            }
            try {
                Instant.parse(Files.readString(marker));
            } catch (RuntimeException e) {
                throw new IOException("备份完成标记无效", e);
            }
        }
        return manifest;
    }

    static JsonNode read(Path directory, ObjectMapper mapper) throws IOException {
        Path file = directory.resolve(FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("缺少备份清单");
        }
        JsonNode manifest = mapper.readTree(Files.readString(file));
        if (manifest == null || !Set.of("2", "3", VERSION).contains(version(manifest))
            || !manifest.path("files").isArray() || manifest.path("files").isEmpty()) {
            throw new IOException("备份清单版本或文件列表无效");
        }
        return manifest;
    }

    static String version(JsonNode manifest) {
        return manifest.path("schemaVersion").asText("");
    }

    static Check verify(Path directory, JsonNode manifest) throws IOException {
        Set<Path> listed = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode item : manifest.path("files")) {
            String name = item.path("path").asText("");
            Path relative;
            try {
                relative = relative(name);
            } catch (RuntimeException | IOException e) {
                failures.add("清单路径无效: " + name);
                continue;
            }
            if (relative.equals(Path.of(FILE)) || relative.equals(Path.of(COMPLETE)) || !listed.add(relative)) {
                failures.add("清单路径重复或保留: " + name);
                continue;
            }
            Path file = directory.resolve(relative);
            if (!regularFile(directory, relative)) {
                failures.add("文件缺失或路径包含链接: " + name);
                continue;
            }
            long expected = item.path("bytes").asLong(-1);
            if (expected < 0 || Files.size(file) != expected) {
                failures.add("文件大小不匹配: " + name);
                continue;
            }
            if (!item.path("sha256").asText("").equals(sha256(file, "2".equals(version(manifest))))) {
                failures.add("SHA-256 不匹配: " + name);
            }
        }
        if (VERSION.equals(version(manifest))) {
            String storage = manifest.path("storage").asText("");
            boolean database = listed.contains(Path.of(DATABASE));
            if (!(storage.equals("sqlite") && database || storage.equals("files") && !database)) {
                failures.add("备份存储类型与数据库文件不匹配");
            }
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(directory::relativize)
                .filter(path -> !path.equals(Path.of(FILE)) && !path.equals(Path.of(COMPLETE)))
                .filter(path -> !listed.contains(path)).forEach(path -> failures.add("文件未列入清单: " + portable(path)));
        }
        return new Check(listed.size(), List.copyOf(failures));
    }

    static Path relative(String name) throws IOException {
        List<String> parts = List.of(name.split("/", -1));
        if (parts.stream().anyMatch(part -> part.isEmpty() || part.endsWith(".") || part.endsWith(" ")
            || NONPORTABLE.matcher(part).find() || WINDOWS_DEVICE.matcher(part).matches())) {
            throw new IOException("无效的备份相对路径: " + name);
        }
        Path path = Path.of(name);
        if (path.isAbsolute() || !portable(path).equals(name)) {
            throw new IOException("无效的备份相对路径: " + name);
        }
        return path;
    }

    static Path privateDirectories(Path directory) throws IOException {
        try {
            return Files.createDirectories(directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createDirectories(directory);
        }
    }

    static boolean regularFile(Path root, Path relative) {
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        return Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS);
    }

    static String portable(Path path) {
        return StreamSupport.stream(path.spliterator(), false).map(Path::toString).collect(Collectors.joining("/"));
    }

    static String sha256(Path file) throws IOException {
        return sha256(file, false);
    }

    static boolean matches(Path file, JsonNode entry, String version) throws IOException {
        return Files.size(file) == entry.path("bytes").asLong(-1)
            && entry.path("sha256").asText("").equals(sha256(file, "2".equals(version)));
    }

    private static String sha256(Path file, boolean legacy) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (legacy) {
                try (var reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.ISO_8859_1);
                    var writer = new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(), digest),
                        StandardCharsets.UTF_8)) {
                    reader.transferTo(writer);
                }
            } else {
                try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static void deleteTree(Path root) throws IOException {
        FileTrees.delete(root);
    }
}
