package com.pilot.mobile

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PairingScannerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun grantCameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = instrumentation.targetContext.packageName
        if (Build.VERSION.SDK_INT >= 28) instrumentation.uiAutomation.grantRuntimePermission(name, Manifest.permission.CAMERA)
        else android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant $name android.permission.CAMERA")
        ).use { it.readBytes() }
    }

    @Test fun portraitScannerHasExplicitReturnAndCancellationIsQuiet() {
        compose.onNodeWithText("扫码连接电脑").performClick()
        compose.onNodeWithText("扫描电脑上的配对码").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val scanner = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PairingScannerActivity>().single()
            assertEquals(Configuration.ORIENTATION_PORTRAIT, scanner.resources.configuration.orientation)
            assertTrue(scanner.window.decorView.height > scanner.window.decorView.width)
        }
        screenshot("scanner-portrait")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("扫码连接电脑").assertIsDisplayed()
        compose.onNodeWithText("扫码已取消", substring = true).assertDoesNotExist()
    }

    @Test fun scannerCanSwitchToManualCodeAndRecoverFromInvalidInput() {
        compose.onNodeWithText("扫码连接电脑").performClick()
        compose.onNodeWithText("改用配对码连接").performScrollTo().performClick()
        compose.onNodeWithText("电脑生成的配对码").performTextInput("https://example.com")
        compose.onNodeWithText("连接", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("这不是 Pilot 配对码", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("扫码连接电脑").performClick()
        compose.onNodeWithText("扫描电脑上的配对码").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("这不是 Pilot 配对码", substring = true).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
