package com.pilot.core.runtime.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.pilot.core.util.FileTrees;
import com.pilot.core.util.Sha256;

/** 所有下载写入项目私有目录，校验通过后原子发布；不覆盖系统工具链。 */
public final class RuntimeAssets {
    public record Asset(String name, URI uri, String sha256, String home, URI fallback) {
        public Asset(String name, URI uri, String sha256, String home) {
            this(name, uri, sha256, home, null);
        }

        public Asset {
            if (!sha256.matches("[a-f0-9]{64}") || !home.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("运行环境清单无效");
            }
        }
    }

    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private static final long MAX_UNPACKED = 2L * 1024 * 1024 * 1024;
    private static final URI NODE_DISTRIBUTION = URI.create("https://nodejs.org/dist/");
    private final Path root;
    private final Properties manifest;
    private final boolean network;
    private final Consumer<String> progress;
    private final HttpClient http;
    private final Duration downloadTimeout;
    private final boolean localTests;

    public RuntimeAssets(Path root, Properties manifest, boolean network, Consumer<String> progress) {
        this(root, manifest, network, progress, null, Duration.ofMinutes(5), false);
    }

    RuntimeAssets(Path root, Properties manifest, boolean network, Consumer<String> progress, HttpClient http,
        Duration timeout, boolean localTests) {
        this.root = root.toAbsolutePath().normalize();
        this.manifest = manifest;
        this.network = network;
        this.progress = progress;
        this.http = http;
        this.downloadTimeout = timeout;
        this.localTests = localTests;
    }

    public static Properties manifest(Path project) throws IOException {
        Properties value = new Properties();
        try (InputStream embedded = RuntimeAssets.class.getResourceAsStream("/bootstrap/runtime.properties")) {
            if (embedded != null) {
                value.load(embedded);
            } else {
                try (InputStream file = Files
                    .newInputStream(project.resolve("pilot-core/src/main/resources/bootstrap/runtime.properties"))) {
                    value.load(file);
                }
            }
        }
        return value;
    }

    public Asset asset(String name) {
        String url = manifest.getProperty(name + ".url");
        if (url == null) {
            throw new IllegalStateException("此系统架构暂没有经过验证的 " + name + " 自动安装包");
        }
        URI uri = URI.create(url);
        URI relative = NODE_DISTRIBUTION.relativize(uri);
        String fallbackBase = manifest.getProperty("node.fallback.base");
        URI fallback = name.startsWith("node.") && fallbackBase != null && !relative.isAbsolute()
            ? URI.create(fallbackBase).resolve(relative) : null;
        return new Asset(name, uri, manifest.getProperty(name + ".sha256", ""),
            manifest.getProperty(name + ".home", ""), fallback);
    }

    public Path install(Asset asset) throws Exception {
        return install(asset, false);
    }

    public Path install(Asset asset, boolean replaceInvalid) throws Exception {
        Files.createDirectories(root);
        try (var lock = BootstrapLock.acquire(root.resolve(asset.home() + ".lock"), Duration.ofMinutes(15))) {
            return installUnlocked(asset, replaceInvalid);
        }
    }

    private Path installUnlocked(Asset asset, boolean replaceInvalid) throws Exception {
        Path destination = root.resolve(asset.home());
        if (!replaceInvalid && verified(destination, asset)) {
            return destination;
        }
        Files.createDirectories(root);
        Path archive = download(asset);
        Path staging = Files.createTempDirectory(root, ".unpack-");
        try {
            progress.accept("正在解压 " + asset.name());
            if (asset.uri().getPath().endsWith(".zip")) {
                unzip(archive, staging);
            } else {
                // 压缩包已由随源码固定的哈希验证，仍检查条目路径，防止越出私有目录。
                var listing = SourceEnvironment.execute(List.of("tar", "-tzf", archive.toString()), root,
                    System.getenv(), Duration.ofSeconds(30), false);
                if (listing.code() != 0) {
                    throw new IOException("运行环境压缩包无法读取，请检查系统 tar 工具");
                }
                for (String line : listing.output().lines().toList()) {
                    safeEntry(staging, line);
                }
                SourceEnvironment.run(List.of("tar", "-xzf", archive.toString(), "-C", staging.toString()), root,
                    System.getenv(), Duration.ofMinutes(3));
            }
            Path extracted;
            try (var files = Files.list(staging)) {
                var entries = files.toList();
                extracted = entries.size() == 1 && Files.isDirectory(entries.getFirst()) ? entries.getFirst() : staging;
            }
            Files.writeString(extracted.resolve(".pilot-verified"), asset.sha256());
            if (Files.exists(destination)) {
                Files.move(destination, root.resolve(asset.home() + ".invalid-" + UUID.randomUUID()));
            }
            try {
                Files.move(extracted, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(extracted, destination);
            }
            return destination;
        } finally {
            deleteTree(staging);
        }
    }

    public static boolean verified(Path directory, Asset asset) throws IOException {
        Path marker = directory.resolve(".pilot-verified");
        return Files.isRegularFile(marker) && Files.readString(marker).equals(asset.sha256());
    }

    public Path download(Asset asset) throws Exception {
        Files.createDirectories(root);
        Path cached = root.resolve(asset.sha256() + ".archive");
        if (Files.isRegularFile(cached) && digest(cached).equals(asset.sha256())) {
            return cached;
        }
        if (!network) {
            throw new IOException("离线模式缺少 " + asset.name() + "，请联网补齐或预先放入已校验的离线缓存");
        }
        if (Files.getFileStore(root).getUsableSpace() < 1024L * 1024 * 1024) {
            throw new IOException("运行环境缓存目录的剩余空间不足 1 GB");
        }
        try {
            return downloadFrom(asset, asset.uri(), cached);
        } catch (IOException first) {
            if (asset.fallback() == null) {
                throw first;
            }
            progress.accept("首选下载源未完成 " + asset.name() + "，正在使用备用源");
            try {
                return downloadFrom(asset, asset.fallback(), cached);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(first);
                throw fallbackFailure;
            }
        }
    }

    private Path downloadFrom(Asset asset, URI uri, Path cached) throws Exception {
        if (!allowed(uri)) {
            throw new IOException("运行环境下载必须使用 HTTPS");
        }
        Path partial = Files.createTempFile(root, ".download-", ".part");
        progress.accept("正在下载并校验 " + asset.name() + "，首次准备可能需要几分钟");
        try {
            HttpClient client = http == null ? BootstrapHttp.client(System.getenv()) : http;
            var response = client.send(HttpRequest.newBuilder(uri).timeout(downloadTimeout).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body(); var output = Files.newOutputStream(partial)) {
                if (response.statusCode() != 200 || !allowed(response.uri())) {
                    throw new IOException("官方下载失败，状态 " + response.statusCode());
                }
                var copy = new FutureTask<Void>(() -> {
                    byte[] buffer = new byte[64 * 1024];
                    long count = 0;
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        count += size;
                        if (count > MAX_DOWNLOAD) {
                            throw new IOException("运行环境下载超过大小上限");
                        }
                        output.write(buffer, 0, size);
                    }
                    return null;
                });
                Thread.startVirtualThread(copy);
                try {
                    copy.get(downloadTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    input.close();
                    throw new IOException("运行环境下载超时，未安装不完整文件", e);
                } catch (ExecutionException e) {
                    throw new IOException("运行环境下载中断，未安装不完整文件", e.getCause());
                } finally {
                    copy.cancel(true);
                }
            }
            if (!digest(partial).equals(asset.sha256())) {
                throw new IOException("运行环境 SHA-256 校验失败，未安装，请检查网络或更新源码中的环境清单");
            }
            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private boolean allowed(URI uri) {
        return "https".equals(uri.getScheme()) || localTests && "http".equals(uri.getScheme())
            && List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
    }

    public static String digest(Path file) throws Exception {
        return Sha256.hex(file);
    }

    static Path safeEntry(Path destination, String name) throws IOException {
        if (name.startsWith("/") || name.contains("\\") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("压缩包包含非法路径");
        }
        Path target = destination.resolve(name).normalize();
        if (!target.startsWith(destination)) {
            throw new IOException("压缩包条目越出安装目录");
        }
        return target;
    }

    static void unzip(Path archive, Path destination) throws IOException {
        long total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100_000) {
                    throw new IOException("压缩包条目过多");
                }
                Path target = safeEntry(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target)) {
                        int size;
                        while ((size = zip.read(buffer)) != -1) {
                            total += size;
                            if (total > MAX_UNPACKED) {
                                throw new IOException("运行环境解压后超过大小上限");
                            }
                            output.write(buffer, 0, size);
                        }
                    }
                }
            }
            if (entries == 0) {
                throw new IOException("运行环境压缩包为空或不完整");
            }
        }
    }

    static void deleteTree(Path directory) throws IOException {
        FileTrees.deleteIfExists(directory);
    }
}
