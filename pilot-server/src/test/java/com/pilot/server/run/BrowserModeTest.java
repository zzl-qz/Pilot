package com.pilot.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrowserModeTest {

    @Test
    void parsesSlashBrowserWithWordBoundaryAndCaseInsensitivity() {
        assertFalse(BrowserMode.parse(null).active());
        assertFalse(BrowserMode.parse("").active());
        assertFalse(BrowserMode.parse("请用浏览器打开首页").active());
        assertFalse(BrowserMode.parse("/browserx test").active());

        BrowserMode bare = BrowserMode.parse(" /browser ");
        assertTrue(bare.active());
        assertEquals("", bare.prompt());

        BrowserMode withGoal = BrowserMode.parse("/BROWSER  验收 http://localhost:5173 登录流");
        assertTrue(withGoal.active());
        assertEquals("验收 http://localhost:5173 登录流", withGoal.prompt());
    }
}
