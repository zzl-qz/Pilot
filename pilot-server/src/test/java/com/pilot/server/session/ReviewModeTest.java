package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** /review 独立审查模式的识别边界：服务端识别不依赖前端命令面板，直接输入也一样生效。 */
class ReviewModeTest {

    @Test
    void recognizesCommandWithAndWithoutScope() {
        ReviewMode bare = ReviewMode.parse("/review");
        assertTrue(bare.active());
        assertEquals("", bare.scope());
        assertTrue(bare.scopeText().contains("未提交改动"), "默认范围要说明按 git 实查");

        ReviewMode scoped = ReviewMode.parse("/review 重点看登录模块，对比 main 分支");
        assertTrue(scoped.active());
        assertEquals("重点看登录模块，对比 main 分支", scoped.scope());
        assertEquals("重点看登录模块，对比 main 分支", scoped.scopeText());
    }

    @Test
    void toleratesWhitespaceCaseAndNull() {
        assertTrue(ReviewMode.parse("  /review  前后空白  ").active());
        assertEquals("前后空白", ReviewMode.parse("  /review  前后空白  ").scope());
        assertFalse(ReviewMode.parse(null).active());
        assertFalse(ReviewMode.parse("").active());
    }

    @Test
    void rejectsPrefixCollisionsAndOrdinaryMessages() {
        // /reviewx 是别的命令或误输入，不能误入只读模式
        assertFalse(ReviewMode.parse("/reviewx").active());
        assertFalse(ReviewMode.parse("/reviews").active());
        assertFalse(ReviewMode.parse("帮我 review 一下").active());
        assertFalse(ReviewMode.parse("先修复 bug，然后 /review").active());
        assertFalse(ReviewMode.parse("普通任务消息").active());
    }
}
