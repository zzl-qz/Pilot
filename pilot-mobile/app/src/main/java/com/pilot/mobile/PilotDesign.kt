package com.pilot.mobile

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** 与桌面共用中性色、指南针标记和状态语义；尺寸按手机触控与系统字体缩放设计。 */
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF181818),
    background = Color(0xFF141414), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141414), onSurface = Color(0xFFF2F2F2),
    surfaceContainer = Color(0xFF202020), surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF292929), surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFB4B4B4), outline = Color(0xFF646464), outlineVariant = Color(0xFF303030),
    secondary = Color(0xFFB4B4B4), secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFF2F2F2),
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFF2F2F2),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF392322), onErrorContainer = Color(0xFFFFDAD5),
)
private val LightPalette = lightColorScheme(
    primary = Color(0xFF202020), onPrimary = Color.White,
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF202020),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF202020),
    surfaceContainer = Color(0xFFF0F0F0), surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEAEAEA), surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF626262), outline = Color(0xFF7A7A7A), outlineVariant = Color(0xFFE2E2E2),
    secondary = Color(0xFF626262), secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color(0xFF202020),
    primaryContainer = Color(0xFFE8E8E8), onPrimaryContainer = Color(0xFF202020),
    error = Color(0xFFAC302B), errorContainer = Color(0xFFFCEAE7), onErrorContainer = Color(0xFF802823),
)
private val PilotTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
)

data class Appearance(val mode: String = "深色", val change: (String) -> Unit = {})
val LocalAppearance = staticCompositionLocalOf { Appearance() }

@Composable
fun PilotTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("pilot.appearance", 0) }
    var mode by remember { mutableStateOf(preferences.getString("theme", "深色") ?: "深色") }
    val dark = when (mode) { "浅色" -> false; "跟随系统" -> isSystemInDarkTheme(); else -> true }
    SideEffect {
        (context as? androidx.activity.ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }
    CompositionLocalProvider(LocalAppearance provides Appearance(mode) { next ->
        mode = next; preferences.edit().putString("theme", next).apply()
    }) {
        MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette,
            typography = PilotTypography,
            shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
            content = content)
    }
}

/** Material 3 旧版弹层按系统主题设系统栏；手动选择外观时同步独立 Dialog 窗口。 */
@Composable
fun MatchDialogSystemBars() {
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        val provider = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()
        provider?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
fun PilotMark(modifier: Modifier = Modifier.size(24.dp)) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val stroke = size.minDimension * 0.055f
        drawCircle(ink.copy(alpha = 0.65f), radius = size.minDimension * 0.45f, style = Stroke(stroke))
        val path = Path().apply {
            moveTo(size.width * 0.52f, size.height * 0.22f)
            lineTo(size.width * 0.72f, size.height * 0.76f)
            lineTo(size.width * 0.5f, size.height * 0.6f)
            lineTo(size.width * 0.31f, size.height * 0.76f)
            close()
        }
        drawPath(path, ink)
    }
}

@Composable
fun statusColor(status: String): Color = when (status) {
    "RUNNING", "COMPLETED", "connected" -> if (MaterialTheme.colorScheme.background == DarkPalette.background) Color(0xFF8CD5AC) else Color(0xFF26734B)
    "WAITING_APPROVAL", "PAUSED", "INTERRUPTED" -> if (MaterialTheme.colorScheme.background == DarkPalette.background) Color(0xFFE2C38A) else Color(0xFF84601C)
    "FAILED" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).background(statusColor(status), RoundedCornerShape(3.dp)))
}

@Composable
fun SectionTitle(title: String, trailing: String = "", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        if (trailing.isNotBlank()) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PilotPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings() {
    val appearance = LocalAppearance.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("外观")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("跟随系统", "浅色", "深色").forEach { value ->
                FilterChip(selected = appearance.mode == value, onClick = { appearance.change(value) }, label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
