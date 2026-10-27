package com.pilot.server.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 开发时直接读 pilot-web 源码里的页面，改 HTML/JS/CSS 保存后刷新浏览器即可。
 * 找不到源码目录（例如打成 jar 拿到别处跑）就什么都不改，继续用 classpath 里的静态文件。
 */
public class LiveStaticEnvironment implements EnvironmentPostProcessor, Ordered {
    static final String LOCATIONS_KEY = "spring.web.resources.static-locations";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path source = findStaticSource();
        if (source == null) {
            return;
        }
        String location = source.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        // 源码目录放最前，classpath 留着兜底，接口路径不受影响
        String locations = location
                + ",classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/";
        environment.getPropertySources().addFirst(new MapPropertySource(
                "pilotLiveStatic", Map.of(LOCATIONS_KEY, locations)));
    }

    /** 从工作目录往上找几层。IDEA 的工作目录有时是仓库根，有时是 pilot-server。 */
    static Path findStaticSource() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve("pilot-web/src/main/resources/static");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
