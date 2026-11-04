package com.pilot.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

/** fixture 只通过 instrumentation 参数提供，正式 App 不内置测试入口或连接密钥。 */
class MobileSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativePairingTaskAndReconnect() {
        compose.onNodeWithText("扫码连接电脑").assertIsDisplayed()
        screenshot("pairing")
        compose.onNodeWithText("粘贴配对码").performClick()
        compose.onNodeWithText("电脑生成的配对码").performTextInput("错误的配对码")
        compose.onNodeWithText("连接", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("这不是 Pilot 配对码", substring = true).fetchSemanticsNodes().isNotEmpty() }

        val code = requireNotNull(InstrumentationRegistry.getArguments().getString("pairingCode")) { "必须传入隔离测试配对码" }
        compose.onNodeWithText("粘贴配对码").performClick()
        compose.onNodeWithText("电脑生成的配对码").performTextInput(code)
        compose.onNodeWithText("连接", useUnmergedTree = true).performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("移动连接验收").fetchSemanticsNodes().isNotEmpty() }
        screenshot("tasks")
        compose.onNodeWithContentDescription("搜索任务").performTextInput("移动")
        compose.onNodeWithText("移动连接验收").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("正在核对测试夹具").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("允许一次").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("这属于另一个任务").assertDoesNotExist()
        compose.onNodeWithText("允许一次").performClick()
        compose.onNodeWithText("确认允许").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("允许一次").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("资料").performClick()
        compose.onNodeWithText("用量").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("上下文与用量").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1200 / 16000 Tokens · 7.5%").assertIsDisplayed()
        org.junit.Assert.assertTrue("少量内容的资料面板不应撑满整个屏幕", compose.onNodeWithTag("detail-sheet").fetchSemanticsNode().boundsInRoot.height < compose.activity.resources.displayMetrics.heightPixels * 0.8f)
        screenshot("usage", compose.onNodeWithTag("detail-sheet"))
        compose.onNodeWithContentDescription("关闭详情").performClick()
        compose.onNodeWithText("上下文与用量").assertDoesNotExist()
        compose.onNodeWithContentDescription("描述任务或补充要求").performTextInput("请补充边界情况")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("发送") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("发送", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("返回") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回").assertIsNotEnabled()
        compose.onNodeWithContentDescription("描述任务或补充要求").performTextReplacement("下一条尚未发送的指令")
        compose.waitUntil(15000) { compose.onAllNodesWithText("已收到手机指令").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("返回") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("描述任务或补充要求").assertTextContains("下一条尚未发送的指令")
        compose.onNodeWithContentDescription("描述任务或补充要求").performTextClearance()
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
        screenshot("conversation")
        compose.waitUntil(10000) { compose.onAllNodesWithText("引导", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("引导", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("引导", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("暂停").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("继续") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("停止") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("停止", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("已停止").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasContentDescription("返回") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("描述任务或补充要求").performTextInput("模拟断线提交")
        compose.onNodeWithText("发送", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("核对提交结果").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("核对提交结果").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("核对提交结果").fetchSemanticsNodes().isEmpty() && compose.onAllNodes(hasContentDescription("返回") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("描述任务或补充要求").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("描述任务或补充要求").performTextInput("重启后仍在的草稿")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("收件箱").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("搜索任务").assertTextContains("移动")
        compose.onNodeWithContentDescription("搜索任务").performTextClearance()
        compose.onNodeWithText("收件箱").performClick()
        compose.onNodeWithText("这属于另一个任务").assertIsDisplayed()
        screenshot("inbox")
        compose.onNodeWithText("项目", useUnmergedTree = true).performClick()
        compose.onNodeWithText("第二项目").assertIsDisplayed()
        compose.onNodeWithText("设备", useUnmergedTree = true).performClick()
        screenshot("devices")
    }

    @Test fun restoresDeviceAndDraftAfterProcessRestart() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("移动连接验收").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("移动连接验收").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("返回") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("描述任务或补充要求").assertTextContains("重启后仍在的草稿")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("设备").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设备").performClick()
        compose.onNodeWithText("移除连接").performClick()
        compose.onNodeWithText("断开并忘记").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("扫码连接电脑").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun attachmentLimitsAndMultipleComputerStorage() {
        val code = requireNotNull(InstrumentationRegistry.getArguments().getString("pairingCode"))
        compose.onNodeWithText("粘贴配对码").performClick()
        compose.onNodeWithText("电脑生成的配对码").performTextInput(code)
        compose.onNodeWithText("连接", useUnmergedTree = true).performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("移动连接验收").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("移动连接验收").performClick()
        lateinit var model: PilotViewModel
        compose.activityRule.scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[PilotViewModel::class.java] }
        compose.waitUntil(10000) { !model.state.value.busy }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val good = File(context.cacheDir, "边界附件.txt").apply { writeText("附件内容") }
        val large = File(context.cacheDir, "超限附件.bin").apply { outputStream().use { it.write(ByteArray(2 * 1024 * 1024 + 1)) } }
        val exact = File(context.cacheDir, "单文件上限.bin").apply { outputStream().use { it.write(ByteArray(2 * 1024 * 1024)) } }
        val empty = File(context.cacheDir, "空文件.txt").apply { writeText("") }
        val unreadable = android.net.Uri.fromFile(File(context.cacheDir, "不存在的文件.txt"))
        try {
            compose.runOnIdle { model.attach(listOf(android.net.Uri.fromFile(good))) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.attachments.size == 1 }
            compose.runOnIdle { model.attach(listOf(android.net.Uri.fromFile(large))) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.error.contains("2 MB") }
            org.junit.Assert.assertEquals(1, model.state.value.attachments.size)
            compose.runOnIdle { model.attach(listOf(android.net.Uri.fromFile(empty))) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.error.contains("大于 0") }
            org.junit.Assert.assertEquals(1, model.state.value.attachments.size)
            compose.runOnIdle { model.attach(List(16) { unreadable }) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.error.contains("16 个") }
            org.junit.Assert.assertEquals(1, model.state.value.attachments.size)
            compose.runOnIdle { model.clearError(); model.removeAttachment(0) }
            org.junit.Assert.assertTrue(model.state.value.attachments.isEmpty())
            compose.runOnIdle { model.attach(List(3) { android.net.Uri.fromFile(exact) }) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.attachments.size == 3 }
            org.junit.Assert.assertEquals(6 * 1024 * 1024, model.state.value.attachments.sumOf { it.bytes })
            // 已满总量时，第一个额外字节就应失败，不能继续读取后面的无效 URI。
            compose.runOnIdle { model.attach(listOf(android.net.Uri.fromFile(good), unreadable)) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.error.contains("6 MB") }
            org.junit.Assert.assertEquals(3, model.state.value.attachments.size)
            compose.runOnIdle { repeat(3) { model.removeAttachment(0) }; model.clearError(); model.attach(List(16) { android.net.Uri.fromFile(good) }) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.attachments.size == 16 }
            compose.runOnIdle { model.attach(listOf(unreadable)) }
            compose.waitUntil(10000) { !model.state.value.busy && model.state.value.error.contains("16 个") }
            org.junit.Assert.assertEquals(16, model.state.value.attachments.size)
            compose.runOnIdle { repeat(16) { model.removeAttachment(0) }; model.clearError() }
            val store = SecureDeviceStore(context)
            val first = requireNotNull(store.computer())
            kotlinx.coroutines.runBlocking {
                val endpoint = com.pilot.mobile.transport.createEndpoint(computer.iroh.SecretKey.generate().toBytes(), development = false)
                try {
                    val second = PairedComputer(computer.iroh.EndpointTicket.fromAddr(endpoint.addr()).toString(), "第二台测试电脑", emptyList(), false)
                    store.save(second)
                    store.value(first.id, "draft:collision", "第一台电脑")
                    store.value(second.id, "draft:collision", "第二台电脑")
                    org.junit.Assert.assertEquals(2, store.computers().size)
                    compose.runOnIdle { model.switchComputer(second) }
                    compose.waitUntil(5000) { model.state.value.computerId == second.id && !model.state.value.busy }
                    compose.runOnIdle { model.switchComputer(first) }
                    compose.waitUntil(15000) { model.state.value.computerId == first.id && !model.state.value.busy && model.state.value.sessions.isNotEmpty() }
                    org.junit.Assert.assertEquals("第一台电脑", store.value(first.id, "draft:collision"))
                    store.save(first.copy(name = "重命名后的电脑"))
                    org.junit.Assert.assertEquals(2, store.computers().size)
                    store.forget(second.id)
                    org.junit.Assert.assertEquals(1, store.computers().size)
                    org.junit.Assert.assertNull(store.value(second.id, "draft:collision"))
                } finally { endpoint.shutdown() }
            }
        } finally { good.delete(); large.delete(); exact.delete(); empty.delete() }
    }

    private fun screenshot(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        // 底部面板属于独立 Dialog window，使用系统截图避免 PixelCopy 抓到后方窗口。
        val bitmap = if (name == "usage") {
            // 系统 Window 动画不受 Compose 测试时钟控制，等它落定再记录外观。
            android.os.SystemClock.sleep(500)
            requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        } else node.captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
