package com.pilot.mobile

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pilot.mobile.transport.TranscriptRow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 组件尺寸 / 字体 / 主题验收；业务传输另由 MobileSmokeTest 的真实 QUIC 流程验证。 */
class MobileDesignTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[PilotViewModel::class.java]
    private fun task(id: String, title: String, status: String, archived: Boolean = false) = buildJsonObject {
        put("id", id); put("title", title); put("status", status); put("projectId", "pilot"); put("workspace", "C:\\工作区\\pilot"); put("archived", archived)
    }
    private val state get() = MobileState(computer = "我的工作电脑", computerId = "design", status = "直连 · 12 ms", projects = listOf(buildJsonObject {
        put("id", "pilot"); put("name", "Pilot"); put("folders", buildJsonArray { add("C:\\工作区\\pilot") })
    }), sessions = listOf(task("a", "完善手机上的任务管理体验", "RUNNING"), task("b", "核对多设备连接与审批边界", "WAITING_APPROVAL"), task("c", "修复剪贴板附件读取", "COMPLETED"), task("d", "已归档任务", "COMPLETED", true)))

    private fun render(width: Int = 360, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                PilotTheme {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.width(width.dp).fillMaxHeight().testTag("design-frame")) { content() }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun searchFiltersAndEmptyStateAreUsableOnSmallPhone() {
        render(320, 1.3f) { TasksScreen(state, model, {}, {}) }
        compose.onNodeWithText("3 个任务").assertIsDisplayed()
        compose.onNodeWithText("搜索任务").performTextInput("剪贴板")
        compose.onNodeWithText("修复剪贴板附件读取").assertIsDisplayed()
        compose.onNodeWithText("完善手机上的任务管理体验").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("需处理", useUnmergedTree = true).performClick()
        compose.onNodeWithText("核对多设备连接与审批边界").assertIsDisplayed()
        compose.onNodeWithText("1 个任务").assertIsDisplayed()
        compose.onNodeWithText("搜索任务").performTextInput("不存在的任务")
        compose.onNodeWithText("没有匹配的任务").assertIsDisplayed()
        compose.onNodeWithText("0 个任务").assertIsDisplayed()
        screenshot("small-search-empty")
    }

    @Test fun pairingAndApprovalActionsFitWithLargeText() {
        render(320, 1.5f) { ConnectScreen(false, {}, {}) }
        compose.onNodeWithText("扫码连接电脑").performScrollTo().assertIsDisplayed()
        assertTouchHeight("扫码连接电脑")
        compose.onNodeWithText("粘贴配对码").performScrollTo().assertIsDisplayed()
        screenshot("pairing-large-text")
        val approval = json("callId" to "a", "title" to "运行项目验证", "sessionId" to "a", "toolName" to "run_command", "argsSummary" to "在当前工作区运行验证脚本，检查任务与附件流程。")
        render(320, 1.5f) { InboxScreen(state.copy(approvals = listOf(approval)), model) }
        compose.onNodeWithText("允许一次").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNodeWithText("打开任务").assertIsDisplayed()
        assertTouchHeight("允许一次")
        screenshot("approval-large-text")
    }

    @Test fun conversationRendersMarkdownCodeAndCompactComposer() {
        val sample = state.copy(selected = state.sessions[0], phase = "正在执行", runId = "r", rows = listOf(
            TranscriptRow("u", "user", "请检查消息队列，并补充边界测试。"),
            TranscriptRow("a", "assistant", "## 已完成\n\n**草稿与附件**会在刷新后保留。\n\n```sh\nnpm run test:refresh\n```\n\n接下来检查小屏和大字体。"),
        ))
        render(320, 1.3f) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { TaskDetail(sample, model) }
                Composer(sample, model, {}, {})
            }
        }
        compose.onNodeWithText("资料").assertIsDisplayed()
        compose.onNodeWithText("复制代码").performScrollTo().performClick()
        compose.onNodeWithText("已复制").assertIsDisplayed()
        compose.onNodeWithContentDescription("描述任务或补充要求").assertIsDisplayed()
        compose.onNodeWithText("停止").assertIsDisplayed()
        assertTouchHeight("停止")
        screenshot("conversation-small")
    }

    @Test fun themesAndLongComputerNamesRemainReadable() {
        val ticket = computer.iroh.SecretKey.generate().use { key -> key.`public`().use { id -> computer.iroh.EndpointAddr(id, null, emptyList()).use { address -> computer.iroh.EndpointTicket.fromAddr(address).use { it.toString() } } } }
        val computer = PairedComputer(ticket, "开发电脑 · Windows 与 Linux 兼容验证工作区", emptyList(), false)
        render(320, 1.3f) { DevicesScreen(state.copy(computerId = computer.id, computers = listOf(computer)), model, {}, {}) }
        compose.onNodeWithText("浅色").performScrollTo().performClick()
        compose.onNodeWithText("浅色").assertIsSelected()
        screenshot("devices-light")
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("深色").assertIsSelected()
        screenshot("devices-dark")
        render(320, 1.3f) { ProjectsScreen(state, model) }
        compose.onNodeWithText("Pilot").assertIsDisplayed()
        compose.onNodeWithText("新建任务").assertIsDisplayed()
        screenshot("projects-small")
    }

    private fun assertTouchHeight(text: String) {
        val pixels = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.height
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("$text 的触控高度应至少 48dp，实际 ${pixels / density}", pixels / density >= 47.9f)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "design-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
