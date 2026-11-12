package com.pilot.server.session;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

import com.pilot.core.http.BoundedHttp;

/** GitHub 公共技能优先使用有界归档下载；网络或鉴权失败时使用本机 Git。 */
final class SkillDownloader implements SkillInstallService.Cloner {
    static final int MAX_BYTES = 64 * 1024 * 1024;
    private final SkillInstallService.Cloner git;
    private final ArchiveDownload download;

    interface ArchiveDownload {
        void fetch(URI url, Path target) throws Exception;
    }

    SkillDownloader(SkillInstallService.Cloner git) {
        this(git, SkillDownloader::fetch);
    }

    SkillDownloader(SkillInstallService.Cloner git, ArchiveDownload download) {
        this.git = git;
        this.download = download;
    }

    @Override
    public void clone(String url, Path target) throws Exception {
        clone(url, target, null, "");
    }

    @Override
    public void clone(String url, Path target, String ref, String path) throws Exception {
        URI source = URI.create(url);
        if (!"github.com".equalsIgnoreCase(source.getHost())) {
            git.clone(url, target, ref, path);
            return;
        }
        String repo = source.getPath().replaceFirst("\\.git$", "");
        String version = URLEncoder.encode(ref == null ? "HEAD" : ref, StandardCharsets.UTF_8).replace("+", "%20");
        Path archive = target.getParent().resolve("download.zip");
        try {
            try {
                download.fetch(URI.create("https://codeload.github.com" + repo + "/zip/" + version), archive);
            } catch (BoundedHttp.ResponseLimitException failure) {
                throw new SkillInstallService.InstallException("技能归档下载超过 64MB 上限，请使用更小的来源");
            } catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("技能下载已取消");
                }
                git.clone(url, target, ref, path);
                return;
            }
            extract(archive, target, path);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static void fetch(URI url, Path target) throws Exception {
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build()) {
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Pilot-Skill-Installer").GET().build();
            try (var response = BoundedHttp.open(http, request, Duration.ofSeconds(20), MAX_BYTES)) {
                if (response.statusCode() != 200) {
                    throw new IOException("技能归档 HTTP " + response.statusCode());
                }
                Files.copy(response.body(), target);
            }
        }
    }

    static void extract(Path archive, Path target, String selectedPath) throws IOException {
        if (Files.size(archive) > MAX_BYTES) {
            throw new IOException("技能归档超过 64MB 上限");
        }
        Files.createDirectories(target);
        long bytes = 0;
        int entries = 0;
        int files = 0;
        String prefix = null;
        Set<Path> written = new HashSet<>();
        try (var zip = new ZipFile(archive.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("技能解包已取消");
                }
                var item = iterator.nextElement();
                if (++entries > 20000) {
                    throw new IOException("归档目录项过多");
                }
                String name = item.getName();
                if (name.contains("\\") || name.startsWith("/") || name.contains(":")
                    || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw new IOException("归档包含非法路径");
                }
                int slash = name.indexOf('/');
                if (slash < 1) {
                    throw new IOException("归档缺少仓库根目录");
                }
                String root = name.substring(0, slash);
                if (prefix == null) {
                    prefix = root;
                }
                if (!prefix.equals(root)) {
                    throw new IOException("归档包含多个根目录");
                }
                String relative = name.substring(slash + 1);
                if (relative.isEmpty() || item.isDirectory()) {
                    continue;
                }
                if (!selectedPath.isEmpty() && !relative.startsWith(selectedPath + "/")) {
                    continue;
                }
                Path file = target.resolve(relative).normalize();
                if (!file.startsWith(target) || !written.add(file)) {
                    throw new IOException("归档包含越界或重复路径");
                }
                if (++files > 2000) {
                    throw new IOException("技能文件数超过 2000 上限");
                }
                Files.createDirectories(file.getParent());
                try (var input = zip.getInputStream(item); var output = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        bytes += count;
                        if (bytes > MAX_BYTES) {
                            throw new IOException("技能解包体积超过 64MB 上限");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
        if (files == 0) {
            throw new IOException("归档里没有找到指定技能目录");
        }
    }
}
