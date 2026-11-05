package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class BootstrapProcessTest {
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("slow")) {
                Thread.sleep(10_000);
            } else if (args[0].equals("large")) {
                System.out.write(new byte[5 * 1024 * 1024]);
            } else {
                System.out.print(args[0]);
            }
        }
    }

    private List<String> command(String argument) {
        String java =
            Path.of(System.getProperty("java.home"), "bin", BootstrapPlatform.current().executable("java")).toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), argument);
    }

    @Test
    void argumentArraysPreserveSpacesUnicodeAndShellCharacters() throws Exception {
        String argument = "中文 path with spaces & $() %TEMP% ! 引号\"";
        var result = SourceEnvironment.execute(command(argument), Path.of(System.getProperty("user.dir")),
            System.getenv(), Duration.ofSeconds(5), false);
        assertEquals(0, result.code());
        assertEquals(argument, result.output());
    }

    @Test
    void boundedOutputIsDrainedWithoutBlockingChild() throws Exception {
        var result = SourceEnvironment.execute(command("large"), Path.of(System.getProperty("user.dir")),
            System.getenv(), Duration.ofSeconds(5), false);
        assertEquals(0, result.code());
        assertTrue(result.output().length() <= 4 * 1024 * 1024);
    }

    @Test
    void timeoutStopsTheOwnedProcess() {
        assertThrows(IOException.class, () -> SourceEnvironment.execute(command("slow"),
            Path.of(System.getProperty("user.dir")), System.getenv(), Duration.ofMillis(100), false));
    }
}
