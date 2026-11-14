package com.pilot.core.model;

import java.net.URI;
import java.util.Map;
import java.util.function.UnaryOperator;

/** 原生模型引用共用的链接筛选、去重和标题格式。 */
final class NativeModelCitations {
    private NativeModelCitations() {
    }

    static void add(String rawUrl, String title, Map<String, String> citations) {
        try {
            URI citation = URI.create(rawUrl);
            String scheme = citation.getScheme();
            if (citation.getHost() != null && citation.getUserInfo() == null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                String url = citation.toASCIIString();
                citations.putIfAbsent(url, title.isBlank() ? url : title);
            }
        } catch (IllegalArgumentException ignored) {
            // 忽略服务端返回的无效引用地址。
        }
    }

    static void append(StringBuilder text, Map<String, String> citations, UnaryOperator<String> formatUrl) {
        if (!citations.isEmpty()) {
            text.append("\n\n来源：\n");
            citations.forEach((url, title) -> text.append("- [")
                .append(title.replace("[", "\\[").replace("]", "\\]").replace("\n", " ")).append("](")
                .append(formatUrl.apply(url)).append(")\n"));
        }
    }
}
