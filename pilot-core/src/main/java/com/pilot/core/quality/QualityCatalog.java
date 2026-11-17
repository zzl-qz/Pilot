package com.pilot.core.quality;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 质量规则目录的运行时视图。
 * 阈值不写在代码里，统一来自 config/quality/rules.json 生成的 quality/rules.properties，
 * 保证提交门禁和 agent 自查用的是同一套口径：改一处，两边同时生效。
 * 目录缺失或键不存在直接报错，不做兜底——静默退回默认值等于又造了一套标准。
 */
public final class QualityCatalog {
    private static final String RESOURCE = "/quality/rules.properties";
    private static final String LIMIT_SUFFIX = ".limit";
    private static final Properties PROPERTIES = load();

    private QualityCatalog() {
    }

    public static int limit(String ruleId) {
        String raw = PROPERTIES.getProperty(ruleId + LIMIT_SUFFIX);
        if (raw == null) {
            throw new IllegalStateException("质量规则目录缺少规则 " + ruleId + "（" + RESOURCE + "）");
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("质量规则目录里 " + ruleId + " 的阈值不是整数：" + raw, e);
        }
    }

    /** 目录里带阈值的规则条数，供自检日志使用。 */
    public static int limitCount() {
        return PROPERTIES.size();
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream stream = QualityCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("找不到质量规则目录：" + RESOURCE + "；先执行 node scripts/quality-sync.mjs --write");
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取质量规则目录失败：" + RESOURCE, e);
        }
        return properties;
    }
}
