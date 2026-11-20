package com.pilot.server.frontend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrontendStartupTest {

    @Test
    void runningFromClassesCountsAsDevelopment() {
        // surefire 下 code source 是目录，与 IDE / mvn 直接跑 main 的形态一致
        assertTrue(FrontendStartup.runningFromClasses());
    }

    /** 打包态直接用一个指向 jar 的 CodeSource 断言，不需要真的跑 fat jar。 */
    @Test
    void codeSourceInsideJarIsNotDevelopment(@TempDir Path temp) throws IOException {
        Path jar = Files.createFile(temp.resolve("pilot-server-0.0.1-SNAPSHOT.jar"));
        assertFalse(FrontendStartup.runningFromClasses(new CodeSource(jar.toUri().toURL(), (Certificate[])null)));
        assertFalse(FrontendStartup.runningFromClasses(null));
    }

    /** Spring Boot 4 把 fat jar 里的类位置报成 jar:nested:...，不能再当成开发态。 */
    @Test
    void packagedJarLocationsAreNotDevelopment() {
        assertFalse(FrontendStartup
            .isDevelopmentLocation("jar:nested:/opt/pilot/pilot-server-0.0.1-SNAPSHOT.jar/!BOOT-INF/classes/!/"));
        assertFalse(FrontendStartup
            .isDevelopmentLocation("jar:file:/opt/pilot/pilot-server-0.0.1-SNAPSHOT.jar!/BOOT-INF/classes!/"));
        // Windows 盘符形式，路径里带空格时也必须是打包态，不能被 URI 解析细节带偏
        assertFalse(FrontendStartup
            .isDevelopmentLocation("jar:nested:/C:/work/pilot server/target/app.jar/!BOOT-INF/classes/!/"));
        assertFalse(FrontendStartup.isDevelopmentLocation("jar:file:/C:/work/app.jar!/BOOT-INF/classes!/"));
        assertFalse(FrontendStartup.isDevelopmentLocation(null));
        assertFalse(FrontendStartup.isDevelopmentLocation(""));
    }

    @Test
    void classesDirectoryIsStillDevelopment(@TempDir Path temp) throws IOException {
        Path classes = Files.createDirectories(temp.resolve("target").resolve("classes"));
        // CodeSource 的 location 是 URL，两种取字符串的写法都要识别
        assertTrue(FrontendStartup.isDevelopmentLocation(classes.toUri().toString()));
        assertTrue(FrontendStartup.isDevelopmentLocation(classes.toUri().toURL().toString()));

        // 指向 jar 文件本身的 file: URL 是文件不是目录，不算开发态
        Path jar = Files.createFile(temp.resolve("pilot-server-0.0.1-SNAPSHOT.jar"));
        assertFalse(FrontendStartup.isDevelopmentLocation(jar.toUri().toString()));
        assertFalse(FrontendStartup.isDevelopmentLocation(jar.toUri().toURL().toString()));
    }

    @Test
    void developmentDirectoryWithSpacesIsDetected(@TempDir Path temp) throws IOException {
        Path classes = Files.createDirectories(temp.resolve("my project").resolve("classes"));
        assertTrue(FrontendStartup.isDevelopmentLocation(classes.toUri().toString()));
    }
}
