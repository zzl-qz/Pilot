package com.pilot.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 配置文件里的模型默认值。
 *
 * @param options 界面上「模型选择」下拉里给出的候选名；可用 {@code pilot.model.options=a,b,c} 覆盖
 */
@ConfigurationProperties(prefix = "pilot.model")
public record ModelProperties(String apiKey, String endpoint, String model,
                              @DefaultValue({"gpt-6-sol", "gpt-5.1", "gpt-5.1-mini", "deepseek-chat"})
                              List<String> options) {
    public static final List<String> DEFAULT_OPTIONS =
            List.of("gpt-6-sol", "gpt-5.1", "gpt-5.1-mini", "deepseek-chat");

    public ModelProperties {
        options = options == null || options.isEmpty() ? DEFAULT_OPTIONS : List.copyOf(options);
    }
}
