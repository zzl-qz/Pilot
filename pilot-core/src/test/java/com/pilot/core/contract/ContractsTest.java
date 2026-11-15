package com.pilot.core.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 契约的兼容规则：新增兼容，删除或改语义要升大版本。 */
class ContractsTest {

    @Test
    void declaringNothingIsAlwaysCompatible() {
        assertTrue(Contracts.check(null).satisfied());
        assertTrue(Contracts.check(List.of()).satisfied());
    }

    @Test
    void publishedMajorSatisfiesTheDeclaration() {
        assertTrue(Contracts.check(List.of("tool.read_file@1")).satisfied());
        assertTrue(Contracts.check(List.of("hook.post_tool_use@1", "event.run_end@1")).satisfied());
    }

    @Test
    void wrongMajorIsReportedAsMissingNotAsSatisfied() {
        var check = Contracts.check(List.of("tool.read_file@2"));
        assertFalse(check.satisfied());
        assertEquals(List.of("tool.read_file@2"), check.missing());
        assertTrue(check.describe().contains("tool.read_file@2"));
    }

    @Test
    void unknownIdOrMalformedDeclarationNeverCountsAsSatisfied() {
        // 不认识的 id、缺版本号、版本号不是数字，一律按「未知」处理：拿不准就降级。
        var check = Contracts.check(List.of("tool.teleport@1", "tool.read_file", "tool.read_file@x"));
        assertFalse(check.satisfied());
        assertEquals(List.of("tool.teleport@1", "tool.read_file", "tool.read_file@x"), check.unknown());
        assertTrue(check.describe().contains("核心不认识的契约"));
    }

    @Test
    void catalogueExposesVersionedReferences() {
        var catalogue = Contracts.catalogue();
        assertTrue(catalogue.containsKey("tool.read_file@1"));
        assertTrue(catalogue.containsKey("event.run_end@1"));
        assertEquals(Contracts.publishedContracts().size(), catalogue.size());
    }
}
