package com.pilot.server.mobile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.pilot.core.runtime.bootstrap.BootstrapPlatform;
import com.pilot.core.runtime.bootstrap.SourceEnvironment;
import com.pilot.server.config.PilotDataDir;

/** IDE 直接启动也执行检查；后台下载与构建不占用 HTTP 请求线程。 */
@Component
public final class MobileEnvironment {
    public record Status(String state, String message, boolean ready) {
    }

    private final Path cache;
    private final String libraries;
    private final boolean enabled;
    private final ExecutorService worker =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("pilot-environment-prepare").factory());
    private volatile Status status;
    private volatile SourceEnvironment.Mobile prepared;
    private boolean preparing;
    private boolean closed;
    private final Preparation preparation;

    @FunctionalInterface
    interface Preparation {
        SourceEnvironment.Mobile prepare(Path root, Path cache, String libraries, Consumer<String> progress)
            throws Exception;
    }

    @Autowired
    public MobileEnvironment(PilotDataDir data,
        @Value("${pilot.mobile.provision:${pilot.runtime.provision:true}}") boolean enabled,
        @Value("${pilot.mobile.libraries:}") String libraries) {
        this(data, enabled, libraries,
            (root, cache, configured,
                progress) -> new SourceEnvironment(root, cache,
                    !"true".equalsIgnoreCase(System.getenv("PILOT_OFFLINE")), progress)
                    .prepareMobile(configured.isBlank() ? null : Path.of(configured)));
    }

    MobileEnvironment(PilotDataDir data, boolean enabled, String libraries, Preparation preparation) {
        String custom = System.getenv("PILOT_BOOTSTRAP_DIR");
        this.cache = custom == null || custom.isBlank() ? data.file("bootstrap") : Path.of(custom);
        this.libraries = libraries;
        this.enabled = enabled;
        this.preparation = preparation;
        status = enabled ? new Status("preparing", "正在检查手机连接所需环境", false) : new Status("disabled", "已关闭自动环境补全", true);
    }

    @EventListener
    public void start(WebServerInitializedEvent event) {
        if (enabled && "true".equals(System.getenv("PILOT_BOOTSTRAP_PREPARED"))) {
            try {
                var result = SourceEnvironment.preparedMobile(cache);
                if (libraries.isBlank() || result.libraries() != null && result.libraries().toAbsolutePath().normalize()
                    .equals(Path.of(libraries).toAbsolutePath().normalize())) {
                    complete(result);
                    return;
                }
            } catch (Exception e) {
                // 源码启动器未留下完整结果时重新检查，不把缺失报告当成功。
            }
        }
        repair();
    }

    public Status status() {
        return status;
    }

    public SourceEnvironment.Mobile prepared() {
        return prepared;
    }

    public synchronized void repair() {
        if (!enabled || preparing || closed) {
            return;
        }
        preparing = true;
        status = new Status("preparing", "正在检查与补全手机连接环境", false);
        worker.submit(() -> {
            try {
                Path root = projectRoot(Path.of(System.getProperty("user.dir")));
                complete(preparation.prepare(root, cache, libraries,
                    message -> status = new Status("preparing", message, false)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed("环境准备已中断，可稍后重新检查");
            } catch (Exception e) {
                // 路径与下载认证可能出现在原始异常中；前端只接收受控的处理说明。
                failed("环境自动补全未完成，请检查网络、缓存目录权限或系统授权后重试");
            }
        });
    }

    private synchronized void complete(SourceEnvironment.Mobile result) {
        prepared = result;
        if (result.ready() && result.javaHome() != null && BootstrapPlatform.current().windows()
            && "x64".equals(BootstrapPlatform.current().arch())) {
            System.setProperty("pilot.mobile.runtime.bin", result.javaHome().resolve("bin").toString());
        }
        preparing = false;
        status = new Status(result.ready() ? "ready" : "failed", result.message(), result.ready());
    }

    private synchronized void failed(String message) {
        preparing = false;
        status = new Status("failed", message, false);
    }

    static Path projectRoot(Path initial) {
        Path path = initial.toAbsolutePath().normalize();
        for (int depth = 0; depth < 5 && path != null; depth++, path = path.getParent()) {
            if (Files.isDirectory(path.resolve("pilot-mobile/bridge/src"))
                || Files.isDirectory(path.resolve("mobile-bridge/lib"))) {
                return path;
            }
        }
        return initial.toAbsolutePath().normalize();
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        worker.shutdownNow();
    }
}
