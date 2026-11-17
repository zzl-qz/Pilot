package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 规则目录的运行时可读性。
 * 这里的期望值来自 config/quality/rules.json，改阈值时要连测试一起改——
 * 这正是目的：让「悄悄改一个数字」变成一次显式的、有记录的改动。
 */
class QualityCatalogTest {
    @Test
    void readsThresholdsGeneratedFromCatalog() {
        assertEquals(80, QualityCatalog.limit("long-method"));
        assertEquals(7, QualityCatalog.limit("too-many-parameters"));
        assertEquals(5, QualityCatalog.limit("deep-nesting"));
        assertEquals(7, QualityCatalog.limit("duplicate-block"));
        assertEquals(6, QualityCatalog.limit("else-if-chain"));
        assertEquals(3, QualityCatalog.limit("repeated-literal"));
        assertTrue(QualityCatalog.limitCount() >= 6, "生成的目录至少要覆盖六个阈值");
    }

    @Test
    void rejectsUnknownRuleInsteadOfSilentlyDefaulting() {
        assertThrows(IllegalStateException.class, () -> QualityCatalog.limit("no-such-rule"));
    }

    @Test
    void auditEngineUsesCatalogValues() {
        // 自查口径和提交门禁共用一份数据；这里锁住「代码里没有第二份数字」。
        assertEquals(QualityCatalog.limit("long-method"), CodeQualityCheck.LONG_METHOD_LINES);
        assertEquals(QualityCatalog.limit("duplicate-block"), CodeQualityCheck.DUPLICATE_BLOCK_LINES);
    }
}
