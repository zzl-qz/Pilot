package com.pilot.server.desktop;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.server.browser.BrowserRuntime;

/** 网页版点按钮时，拉起内置 Chromium 窗口。桌面壳不走这里。 */
@RestController
@RequestMapping("/api/desktop")
public class DesktopWindowController {
    private final BrowserRuntime browser;

    public DesktopWindowController(BrowserRuntime browser) {
        this.browser = browser;
    }

    /** 网页版：点按钮才拉起内置 Chromium 窗口。桌面壳不走这里。 */
    @PostMapping("/browser-window")
    public Map<String, Object> browserWindow() {
        try {
            var reply = browser.execute("user-window", Map.of("op", "open_window"), Duration.ofSeconds(60));
            String text = reply.text() == null ? "已打开浏览器窗口。" : reply.text();
            return Map.of("ok", true, "message", text);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage() == null ? "没能打开浏览器窗口" : e.getMessage());
        }
    }
}
