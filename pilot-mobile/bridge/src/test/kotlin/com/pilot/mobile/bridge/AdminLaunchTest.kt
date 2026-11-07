package com.pilot.mobile.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminLaunchTest {
    @Test fun `电脑端启动不弹独立配对管理页也不打印诊断地址`() {
        assertFalse(diagnosticAdminUrlRequested(emptyArray()))
        assertFalse(diagnosticAdminUrlRequested(arrayOf("--pilot-port", "9999", "--data-dir", ".pilot/mobile")))
        assertFalse(diagnosticAdminUrlRequested(arrayOf("--no-open", "--parent-pid", "123", "--name", "我的电脑")))
        assertFalse(diagnosticAdminUrlRequested(arrayOf("--open-admin", "--no-open")))
    }

    @Test fun `只有显式诊断调用才打印可手动复制的管理地址`() {
        assertTrue(diagnosticAdminUrlRequested(arrayOf("--open-admin")))
        assertTrue(diagnosticAdminUrlRequested(arrayOf("--pilot-port", "9999", "--open-admin")))
    }
}
