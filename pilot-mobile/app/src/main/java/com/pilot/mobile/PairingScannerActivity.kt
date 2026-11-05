package com.pilot.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** 内部扫码页不开放外部 Intent；扫描结果只回到发起连接的页面。 */
class PairingScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PilotTheme {
                PairingScanner(
                    cancel = { finish() },
                    paste = { finishWith(PairingScanContract.PASTE, "") },
                    scanned = { finishWith(PairingScanContract.CODE, it) },
                )
            }
        }
    }

    private fun finishWith(action: String, code: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(action, if (action == PairingScanContract.CODE) code else "true"))
        finish()
    }
}

sealed interface PairingScanResult {
    data class Code(val value: String) : PairingScanResult
    data object Paste : PairingScanResult
    data object Cancelled : PairingScanResult
}

class PairingScanContract : ActivityResultContract<Unit, PairingScanResult>() {
    override fun createIntent(context: Context, input: Unit) = Intent(context, PairingScannerActivity::class.java)
    override fun parseResult(resultCode: Int, intent: Intent?): PairingScanResult = when {
        resultCode != Activity.RESULT_OK -> PairingScanResult.Cancelled
        intent?.hasExtra(PASTE) == true -> PairingScanResult.Paste
        !intent?.getStringExtra(CODE).isNullOrBlank() -> PairingScanResult.Code(intent!!.getStringExtra(CODE)!!)
        else -> PairingScanResult.Cancelled
    }

    companion object {
        internal const val CODE = "com.pilot.mobile.SCAN_CODE"
        internal const val PASTE = "com.pilot.mobile.PASTE_CODE"
    }
}

@Composable
private fun PairingScanner(cancel: () -> Unit, paste: () -> Unit, scanned: (String) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hasCamera = remember { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val hasFlash = remember { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }
    fun permitted() = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(permitted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var scanError by remember { mutableStateOf("") }
    val gate = remember { PairingScanGate() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = permitted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (hasCamera && !granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    BackHandler(onBack = cancel)
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = cancel) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("连接电脑", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("扫描电脑上的配对码", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("在电脑 Pilot 左侧打开“连接手机”，将二维码放入取景框。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    when {
                        !hasCamera || !granted || cameraError -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                                Icon(Icons.Outlined.CameraAlt, null, Modifier.size(32.dp))
                                Text(when { !hasCamera -> "此设备没有可用相机"; !granted -> "需要相机权限才能扫码"; else -> "相机暂不可用" }, style = MaterialTheme.typography.titleMedium)
                                Text("也可以直接使用电脑生成的配对码连接。", style = MaterialTheme.typography.bodyMedium)
                                if (hasCamera && !granted) Button(onClick = {
                                    if (denied && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                                        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                                    } else { asked = true; permission.launch(Manifest.permission.CAMERA) }
                                }) { Text(if (denied && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) "打开权限设置" else "允许使用相机") }
                                if (hasCamera && granted && cameraError) Button(onClick = { cameraError = false; torch = false; retry++ }) { Text("重试相机") }
                            }
                        }
                        else -> key(retry) {
                            CameraScanner(torch, onFailure = { cameraError = true; torch = false }) { raw ->
                                when (val result = gate.accept(raw)) {
                                    is PairingScanDecision.Valid -> scanned(result.code)
                                    is PairingScanDecision.Invalid -> scanError = result.message
                                    PairingScanDecision.Ignored -> Unit
                                }
                            }
                        }
                    }
                }
                if (scanError.isNotBlank()) Text(scanError, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (hasFlash && granted && !cameraError) TextButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (torch) "关闭手电筒" else "打开手电筒")
                }
                TextButton(onClick = paste, modifier = Modifier.heightIn(min = 48.dp)) { Text("改用配对码连接") }
                Text("不同网络时，请等待电脑的跨网服务就绪后扫码。配对码仅连接生成它的电脑。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CameraScanner(torch: Boolean, onFailure: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentCode by rememberUpdatedState(onCode)
    val currentFailure by rememberUpdatedState(onFailure)
    val view = remember {
        DecoratedBarcodeView(context).apply {
            statusView.visibility = View.GONE
            barcodeView.isUseTextureView = true
            barcodeView.marginFraction = 0.12
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            cameraSettings = cameraSettings.apply { isAutoFocusEnabled = true }
            barcodeView.addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() {}
                override fun previewStarted() {}
                override fun previewStopped() {}
                override fun cameraClosed() {}
                override fun cameraError(error: Exception) { currentFailure() }
            })
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) { currentCode(result.text) }
            })
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize(), update = { if (torch) it.setTorchOn() else it.setTorchOff() })
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.resume()
            if (event == Lifecycle.Event.ON_PAUSE) view.pause()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose { lifecycle.removeObserver(observer); view.pause() }
    }
}
