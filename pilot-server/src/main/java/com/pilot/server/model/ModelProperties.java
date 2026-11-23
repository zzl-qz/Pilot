package com.pilot.server.model;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置文件里的模型默认值。
 *
 * @param options 界面上「模型选择」下拉里给出的候选名；可用 {@code pilot.model.options=a,b,c} 覆盖
 */
@ConfigurationProperties(prefix = "pilot.model")
public record ModelProperties(String apiKey, String endpoint, String model, List<String> options) {
    public static final List<String> DEFAULT_OPTIONS = List.of("gpt-6-sol", "gpt-5.1", "gpt-5.1-mini", "deepseek-chat");

    public ModelProperties {
        options = options == null || options.isEmpty() ? DEFAULT_OPTIONS : List.copyOf(options);
    }

    /** 覆盖默认 record.toString()，避免 Spring 配置诊断或日志打印明文密钥。 */
    @Override
    public String toString() {
        return "ModelProperties[apiKey=" + (apiKey != null && !apiKey.isBlank() ? "***" : "") + ", endpoint=" + endpoint
            + ", model=" + model + ", options=" + options + "]";
    }
}
