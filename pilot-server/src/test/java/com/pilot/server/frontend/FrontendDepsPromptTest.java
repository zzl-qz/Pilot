package com.pilot.server.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrontendDepsPromptTest {

    @Test
    void onlyExplicitYesIsAccepted() {
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("2"));
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice(" 2 "));
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("Y"));
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("yes"));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice("1"));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice(""));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice(null));
        assertEquals(FrontendDepsPrompt.NO, FrontendDepsPrompt.parseChoice("随便"));
        // 管道重定向偶尔会在前面塞 BOM
        assertEquals(FrontendDepsPrompt.YES, FrontendDepsPrompt.parseChoice("\uFEFF2"));
    }

    @Test
    void enterConfirmsTypedChoice() {
        assertEquals(FrontendDepsPrompt.YES, read("2\n"));
        assertEquals(FrontendDepsPrompt.NO, read("\n"));
        assertEquals(FrontendDepsPrompt.NO, read("1\r\n"));
    }

    @Test
    void silentInputFallsBackToNoWithoutHoldingStartup() {
        long start = System.nanoTime();
        int choice = FrontendDepsPrompt.readChoice(new ByteArrayInputStream(new byte[0]), false, 0);
        assertEquals(FrontendDepsPrompt.NO, choice);
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "超时不能把启动拖住");
    }

    @Test
    void runningFromClassesCountsAsDevelopment() {
        // surefire 下 code source 是目录，与 IDE / mvn 直接跑 main 的形态一致
        assertTrue(FrontendDepsPrompt.runningFromClasses());
    }

    /** 打包态直接用一个指向 jar 的 CodeSource 断言，不需要真的跑 fat jar。 */
    @Test
    void codeSourceInsideJarIsNotDevelopment(@TempDir Path temp) throws IOException {
        Path jar = Files.createFile(temp.resolve("pilot-server-0.0.1-SNAPSHOT.jar"));
        assertFalse(FrontendDepsPrompt.runningFromClasses(new CodeSource(jar.toUri().toURL(), (Certificate[])null)));
        assertFalse(FrontendDepsPrompt.runningFromClasses(null));
    }

    /** Spring Boot 4 把 fat jar 里的类位置报成 jar:nested:...，不能再当成开发态。 */
    @Test
    void packagedJarLocationsAreNotDevelopment() {
        assertFalse(FrontendDepsPrompt
            .isDevelopmentLocation("jar:nested:/opt/pilot/pilot-server-0.0.1-SNAPSHOT.jar/!BOOT-INF/classes/!/"));
        assertFalse(FrontendDepsPrompt
            .isDevelopmentLocation("jar:file:/opt/pilot/pilot-server-0.0.1-SNAPSHOT.jar!/BOOT-INF/classes!/"));
        // Windows 盘符形式，路径里带空格时也必须是打包态，不能被 URI 解析细节带偏
        assertFalse(FrontendDepsPrompt
            .isDevelopmentLocation("jar:nested:/C:/work/pilot server/target/app.jar/!BOOT-INF/classes/!/"));
        assertFalse(FrontendDepsPrompt.isDevelopmentLocation("jar:file:/C:/work/app.jar!/BOOT-INF/classes!/"));
        assertFalse(FrontendDepsPrompt.isDevelopmentLocation(null));
        assertFalse(FrontendDepsPrompt.isDevelopmentLocation(""));
    }

    @Test
    void classesDirectoryIsStillDevelopment(@TempDir Path temp) throws IOException {
        Path classes = Files.createDirectories(temp.resolve("target").resolve("classes"));
        // CodeSource 的 location 是 URL，两种取字符串的写法都要识别
        assertTrue(FrontendDepsPrompt.isDevelopmentLocation(classes.toUri().toString()));
        assertTrue(FrontendDepsPrompt.isDevelopmentLocation(classes.toUri().toURL().toString()));

        // 指向 jar 文件本身的 file: URL 是文件不是目录，不算开发态
        Path jar = Files.createFile(temp.resolve("pilot-server-0.0.1-SNAPSHOT.jar"));
        assertFalse(FrontendDepsPrompt.isDevelopmentLocation(jar.toUri().toString()));
        assertFalse(FrontendDepsPrompt.isDevelopmentLocation(jar.toUri().toURL().toString()));
    }

    @Test
    void developmentDirectoryWithSpacesIsDetected(@TempDir Path temp) throws IOException {
        Path classes = Files.createDirectories(temp.resolve("my project").resolve("classes"));
        assertTrue(FrontendDepsPrompt.isDevelopmentLocation(classes.toUri().toString()));
    }

    private static int read(String text) {
        return FrontendDepsPrompt.readChoice(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), false, 1);
    }
}
