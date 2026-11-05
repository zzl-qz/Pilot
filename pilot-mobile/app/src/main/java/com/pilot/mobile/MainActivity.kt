package com.pilot.mobile

import android.os.Bundle
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pilot.mobile.transport.text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PilotTheme { PilotApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PilotApp(model: PilotViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) model.setForeground(false)
        }
        lifecycle.addObserver(observer)
        model.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model.setForeground(false) }
    }
    var showCode by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var taskMenu by remember { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    val scanner = rememberLauncherForActivityResult(PairingScanContract()) { result ->
        when (result) {
            is PairingScanResult.Code -> model.pair(result.value)
            PairingScanResult.Paste -> showCode = true
            PairingScanResult.Cancelled -> Unit
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.attach(uris) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) model.draft(state.draft + if (state.draft.isBlank()) text else "\n$text")
        else model.report("未录入文字。可以重新录音，或使用键盘输入。")
    }
    val scan: () -> Unit = {
        try { model.clearError(); scanner.launch(Unit) }
        catch (_: Exception) { model.report("此设备无法打开相机，请粘贴配对码。") }
    }
    BackHandler(state.detail != null || state.selected != null) { if (state.detail != null) model.closeDetail() else model.back() }
    Scaffold(
        topBar = {
            Surface {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.selected != null) IconButton(onClick = model::back, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(22.dp)) }
                    else if (state.computer.isBlank()) { PilotMark(Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)) }
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(state.selected?.text("title")?.ifBlank { "新任务" } ?: if (state.computer.isBlank()) "Pilot" else state.tab,
                            Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = if (state.selected == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        if (state.computer.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusDot(if (state.networkError.isNotBlank()) "FAILED" else if (state.status.contains("直连") || state.status.contains("中继")) "connected" else "")
                            Text("${if (state.selected == null) "Pilot · " else ""}${state.computer} · ${state.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else Text("你的随身工作台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.selected != null) Box {
                        IconButton(onClick = { taskMenu = true }, enabled = !state.busy) { Icon(Icons.Outlined.MoreHoriz, "任务选项") }
                        DropdownMenu(taskMenu, onDismissRequest = { taskMenu = false }) {
                            DropdownMenuItem(text = { Text("重命名任务") }, onClick = { taskMenu = false; rename = state.selected; renameText = state.selected!!.text("title") })
                            DropdownMenuItem(text = { Text(if (state.syncing) "正在同步…" else "刷新连接") }, enabled = !state.syncing, onClick = { taskMenu = false; model.retry() })
                        }
                    } else if (state.computer.isNotBlank()) {
                        IconButton(onClick = model::retry, enabled = !state.busy && !state.syncing) {
                            if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "正在同步" }, strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Refresh, "刷新数据", Modifier.size(20.dp))
                        }
                        if (state.tab == "任务") Button(onClick = { showProjects = true }, enabled = !state.busy && !(state.syncing && !state.hasSnapshot), contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新任务")
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (state.selected != null) Composer(state, model,
                onAttach = { try { files.launch(arrayOf("*/*")) } catch (_: Exception) { model.report("没有可用的文件选择器，请在电脑端添加附件。") } },
                onVoice = {
                    try { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "说出任务或补充要求")) }
                    catch (_: Exception) { model.report("设备没有可用的语音输入服务，可使用输入法麦克风或键盘输入。") }
                })
            else if (state.computer.isNotBlank()) Surface {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        listOf("任务" to Icons.Outlined.Checklist, "项目" to Icons.Outlined.Folder, "收件箱" to Icons.Outlined.Inbox, "设备" to Icons.Outlined.Devices).forEach { (tab, icon) ->
                            val color = if (state.tab == tab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(Modifier.weight(1f).selectable(selected = state.tab == tab, onClick = { model.tab(tab) }, enabled = !state.busy, role = Role.Tab).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                BadgedBox(badge = { if (tab == "收件箱" && state.approvals.isNotEmpty()) Badge(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface) { Text(state.approvals.size.toString()) } }) { Icon(icon, null, Modifier.size(22.dp), tint = color) }
                                Text(tab, color = color, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        // 宽屏限制阅读行长；手机占满可用区域。两侧各 0/16dp，主体总宽不超过 840dp。
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val errorText = state.error.ifBlank { state.networkError }
            if (errorText.isNotBlank()) ErrorNotice(errorText, model::retry, model::clearError)
            if (state.pending != null && !state.busy) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("有一条指令的结果尚未确认", style = MaterialTheme.typography.labelLarge)
                    Row { TextButton(onClick = model::retryPending) { Text("核对提交结果") }; TextButton(onClick = { showDiscard = true }) { Text("已自行核实") } }
                }
            }
            when {
                state.computer.isBlank() -> ConnectScreen(state.busy, scan, { showCode = true })
                state.selected != null -> TaskDetail(state, model)
                state.syncing && !state.hasSnapshot && state.tab != "设备" -> EmptyState("正在同步工作台", "首次连接需要读取电脑上的项目和任务，请稍候。")
                else -> screenState.SaveableStateProvider("${state.computerId}:${state.tab}") {
                    when (state.tab) {
                        "任务" -> TasksScreen(state, model, { showProjects = true }, { rename = it; renameText = it.text("title") })
                        "项目" -> ProjectsScreen(state, model)
                        "收件箱" -> InboxScreen(state, model)
                        else -> DevicesScreen(state, model, scan, { showCode = true })
                    }
                }
            }
        }
        }
    }
    if (showCode) AlertDialog(onDismissRequest = { showCode = false; code = "" }, title = { Text("粘贴配对码") }, text = {
        OutlinedTextField(value = code, onValueChange = { code = it.take(8192) }, label = { Text("电脑生成的配对码") }, maxLines = 5)
    }, confirmButton = { TextButton(onClick = { showCode = false; model.pair(code); code = "" }, enabled = code.isNotBlank() && !state.busy) { Text("连接") } }, dismissButton = { TextButton(onClick = { showCode = false; code = "" }) { Text("取消") } })
    if (showProjects) AlertDialog(onDismissRequest = { showProjects = false }, title = { Text("在哪个项目中新建任务？") }, text = {
        LazyColumn {
            if (state.projects.isEmpty()) item { Text("还没有项目。请到“项目”添加电脑上的目录。") }
            items(state.projects) { project -> TextButton(onClick = { showProjects = false; model.create(project) }, modifier = Modifier.fillMaxWidth()) { Text(project.text("name")) } }
        }
    }, confirmButton = { TextButton(onClick = { showProjects = false }) { Text("关闭") } })
    state.blankProject?.let { project -> AlertDialog(onDismissRequest = model::dismissBlank, title = { Text("项目中已有空白任务") }, text = { Text("可以继续填写现有任务，或归档它再新建。已有内容不会被删除。") },
        confirmButton = { TextButton(onClick = { model.dismissBlank(); model.openTask(project.text("blankSessionId")) }) { Text("打开现有任务") } },
        dismissButton = { TextButton(onClick = { model.create(project, true) }) { Text("归档后新建") } }) }
    if (rename != null) AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名任务") }, text = { OutlinedTextField(renameText, { renameText = it.take(200) }, label = { Text("任务名称") }) },
        confirmButton = { TextButton(onClick = { model.rename(rename!!, renameText); rename = null }, enabled = renameText.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("已在任务记录中核实？") }, text = { Text("取消待核对提示后可以提交新指令。请先确认上次指令是否已经执行，避免重复操作。") },
        confirmButton = { TextButton(onClick = { showDiscard = false; model.dismissPending() }) { Text("确认已核实") } }, dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("返回") } })
    if (state.detail != null) DetailSheet(state, model)
}
