package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionIsolation;
import com.pilot.core.exec.ExecutionLimits;
import com.pilot.core.exec.ExecutionSettings;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.MultiAgentProperties;

/** 需显式启用并准备镜像；网络下载和真实编译，不调用模型 API。 */
@EnabledIfSystemProperty(named = "pilot.test.project-builds", matches = "true")
class ProjectBuildIntegrationTest {
    @TempDir
    Path root;
    @TempDir
    Path data;

    private CommandVerificationGate gate(String image, boolean network) {
        var settings = new ExecutionSettings(ExecutionIsolation.CONTAINER, image, "docker", network,
            new ExecutionLimits(240, 100_000, 2048, 2, 256), data.resolve("build-cache"));
        var selection = ExecutionBackends.select(settings);
        assertTrue(selection.isolated() && !selection.degraded(), selection.reason());
        return new CommandVerificationGate(new MultiAgentProperties(true, 2, 1, 10, 240, List.of()),
            new PilotDataDir(data.toString()), selection);
    }

    private void fixture(String name) throws Exception {
        Path fixture = Path.of(getClass().getResource("/build-fixtures/" + name).toURI());
        try (var paths = Files.walk(fixture)) {
            for (Path path : paths.toList()) {
                Path target = root.resolve(fixture.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private DiffSandbox proposal(String path, String before, String after) {
        var box = new DiffSandbox("b", "agent", "task", "");
        box.add(path, before, after, true, RiskLevel.LOW);
        return box;
    }

    private void scenario(String name, String image, String file, String from, String to) throws Exception {
        fixture(name);
        String before = Files.readString(root.resolve(file));
        var good = proposal(file, before, before.replace(from, to));
        var cold = gate(image, false).verify(root, "", good.changes());
        assertEquals("failed", cold.status(), "冷缓存且断网不能假报通过");
        var online = gate(image, true).verify(root, "", good.changes());
        assertTrue(online.ok(), online.toString());
        var offline = gate(image, false).verify(root, "", good.changes());
        assertTrue(offline.ok(), offline.toString());
        var broken = gate(image, false).verify(root, "", proposal(file, before, before + "\n").changes());
        assertEquals("failed", broken.status(), broken.toString());
        assertTrue(broken.checks().stream().anyMatch(step -> step.contains("exit 1")), broken.toString());
        assertEquals(before, Files.readString(root.resolve(file)), "校验不能修改真实源文件");
        assertFalse(Files.exists(root.resolve("target")));
        assertFalse(Files.exists(root.resolve("node_modules")));
        assertFalse(Files.exists(root.resolve("dist")));
        System.out.println(
            "PROJECT_BUILD " + name + " cold-offline=failed online=passed warm-offline=passed regression=failed\n"
                + online.checks() + "\n" + offline.checks());
    }

    @Test
    void mavenColdWarmOfflineAndFailingTests() throws Exception {
        scenario("maven", "maven:3.9-eclipse-temurin-25", "src/main/java/example/Sum.java", "subtractExact",
            "addExact");
    }

    @Test
    void npmColdWarmOfflineAndFailingTests() throws Exception {
        scenario("node", "node:22-alpine", "src/sum.mjs", " - ", " + ");
    }

    @Test
    void missingToolchainAndMissingImageReportActionableFailure() throws Exception {
        fixture("node");
        String file = "src/sum.mjs";
        String before = Files.readString(root.resolve(file));
        var changes = proposal(file, before, before.replace(" - ", " + ")).changes();
        var missingTool = gate("maven:3.9-eclipse-temurin-25", false).verify(root, "", changes);
        assertEquals("failed", missingTool.status());
        assertTrue(missingTool.detail().contains("缺少工具链: node"), missingTool.toString());
        var missingImage = gate("pilot-missing-build-image:never", false).verify(root, "", changes);
        assertEquals("failed", missingImage.status());
        assertTrue(missingImage.detail().contains("镜像未就绪"), missingImage.toString());
    }
}
