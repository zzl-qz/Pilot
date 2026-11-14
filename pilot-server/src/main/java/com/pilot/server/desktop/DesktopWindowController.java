package com.pilot.server.desktop;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.browser.BrowserRuntime;

import lombok.RequiredArgsConstructor;

/** 网页版点按钮时，拉起内置 Chromium 窗口。桌面壳不走这里。 */
@RestController
@RequestMapping("/api/desktop")
@RequiredArgsConstructor
public class DesktopWindowController {
    /** 第一次开窗口可能要先装浏览器，所以给得比较宽。 */
    private static final Duration OPEN_WINDOW_TIMEOUT = Duration.ofSeconds(60);

    private final BrowserRuntime browser;

    /** 网页版：点按钮才拉起内置 Chromium 窗口。桌面壳不走这里。 */
    @PostMapping("/browser-window")
    public Map<String, Object> browserWindow() {
        try {
            var reply = browser.execute("user-window", Map.of("op", "open_window"), OPEN_WINDOW_TIMEOUT);
            String text = reply.text() == null ? "已打开浏览器窗口。" : reply.text();
            return Map.of("ok", true, "message", text);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage() == null ? "没能打开浏览器窗口" : e.getMessage());
        }
    }
}
