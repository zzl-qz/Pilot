package com.pilot.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilot.mobile.transport.text
import kotlinx.serialization.json.*

@Composable
fun ErrorNotice(message: String, retry: () -> Unit, dismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, Modifier.weight(1f).padding(start = 10.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
            }
            TextButton(onClick = retry) { Text("刷新连接") }
        }
    }
}

@Composable
fun ConnectScreen(busy: Boolean, scan: () -> Unit, paste: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            PilotPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.LaptopMac, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(3) { StatusDot("connected") } }
                    Icon(Icons.Outlined.Smartphone, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text("电脑继续执行\n你随时接手", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text("查看任务进度、补充要求，或处理需要你确认的操作。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = scan, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("扫码连接电脑") }
                TextButton(onClick = paste, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("粘贴配对码") }
            }
        }
        item {
            SectionTitle("配对只需一步")
            Text("在电脑 Pilot 的左侧打开“连接手机”，扫描弹出的二维码。配对后，可以管理这台电脑上的全部项目与任务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { InfoNote("电脑需要保持在线。手机和电脑可以使用不同网络，连接可能经由中继。") }
    }
}

@Composable
fun TasksScreen(state: MobileState, model: PilotViewModel, create: () -> Unit, rename: (JsonObject) -> Unit) {
    var query by rememberSaveable(state.computerId) { mutableStateOf("") }
    var filter by rememberSaveable(state.computerId) { mutableStateOf("全部") }
    var menu by remember { mutableStateOf("") }
    val sessions = state.sessions.filter { session ->
        val status = session.text("status")
        val matches = when (filter) {
            "归档" -> session.bool("archived")
            "执行中" -> !session.bool("archived") && status in setOf("RUNNING", "WAITING_APPROVAL")
            "需处理" -> !session.bool("archived") && (status in setOf("FAILED", "INTERRUPTED", "PAUSED", "WAITING_APPROVAL") || state.approvals.any { it.text("sessionId") == session.text("id") })
            "已完成" -> !session.bool("archived") && status == "COMPLETED"
            else -> !session.bool("archived")
        }
        matches && (session.text("title") + session.text("workspace")).contains(query, ignoreCase = true)
    }.sortedByDescending { it.text("id") in state.pins }
    Column {
        TextField(query, { query = it.take(300) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics { contentDescription = "搜索任务" }, singleLine = true,
            placeholder = { Text("搜索任务") }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp)) } },
            shape = RoundedCornerShape(12.dp), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            listOf("全部", "执行中", "需处理", "已完成", "归档").forEach { label ->
                Column(Modifier.widthIn(min = 48.dp).width(IntrinsicSize.Min).heightIn(min = 48.dp).selectable(selected = filter == label, role = Role.Tab, onClick = { filter = label }), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, Modifier.padding(top = 12.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge, color = if (filter == label) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (filter == label) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { SectionTitle(if (query.isNotBlank()) "搜索结果" else if (filter == "全部") "最近任务" else filter, "${sessions.size} 个任务", Modifier.padding(horizontal = 20.dp)) }
            if (sessions.isEmpty()) item { EmptyState(if (query.isBlank()) "这里还没有任务" else "没有匹配的任务", "可以更换筛选条件，或选择项目开始新任务。", "新任务", create) }
            items(sessions, key = { it.text("id") }) { session ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.select(session) }.padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(session.text("status")); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (session.text("id") in state.pins) { Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(session.text("title").ifBlank { "新任务" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        val project = state.projects.firstOrNull { it.text("id") == session.text("projectId") }?.text("name") ?: "未分组"
                        Text("$project · ${statusLabel(session.text("status"))}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = session.text("id") }, enabled = !state.busy) { Icon(Icons.Outlined.MoreHoriz, "任务操作：${session.text("title")}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menu == session.text("id"), onDismissRequest = { menu = "" }) {
                            DropdownMenuItem(text = { Text(if (session.text("id") in state.pins) "取消置顶" else "置顶") }, onClick = { menu = ""; model.pin(session.text("id")) })
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = ""; rename(session) })
                            DropdownMenuItem(text = { Text(if (session.bool("archived")) "恢复任务" else "归档任务") }, onClick = { menu = ""; model.archive(session) })
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 38.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun ProjectsScreen(state: MobileState, model: PilotViewModel) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<JsonObject?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.projects.size} 个工作区", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { adding = true; name = ""; folder = "" }, enabled = !state.busy) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加项目") }
            }
        }
        if (state.projects.isEmpty()) item { EmptyState("从一个项目开始", "添加电脑上的目录后，就能从手机发起任务。") }
        items(state.projects, key = { it.text("id") }) { project ->
            PilotPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp))
                    Text(project.text("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { renaming = project; name = project.text("name") }, enabled = !state.busy) { Icon(Icons.Outlined.Edit, "重命名项目 ${project.text("name")}", Modifier.size(18.dp)) }
                }
                project["folders"]?.jsonArray?.forEach { Text(it.jsonPrimitive.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                val tasks = state.sessions.filter { it.text("projectId") == project.text("id") && !it.bool("archived") }
                Text("${tasks.size} 个任务", Modifier.padding(top = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tasks.take(5).forEach { task ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !state.busy) { model.select(task) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(task.text("status"))
                        Text(task.text("title").ifBlank { "新任务" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = { model.create(project) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("新建任务") }
            }
        }
    }
    if (adding || renaming != null) AlertDialog(onDismissRequest = { adding = false; renaming = null }, title = { Text(if (adding) "添加项目" else "重命名项目") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("项目名称") })
            if (adding) { Text(folder.ifBlank { "请选择电脑上的目录" }, fontSize = 13.sp); OutlinedButton(onClick = { model.browse() }, enabled = !state.busy) { Text("浏览电脑目录") } }
        }
    }, confirmButton = { TextButton(onClick = {
        if (adding) model.createProject(name, folder) else model.renameProject(renaming!!, name)
        adding = false; renaming = null
    }, enabled = !state.busy && name.isNotBlank() && (!adding || folder.isNotBlank())) { Text("保存") } }, dismissButton = { TextButton(onClick = { adding = false; renaming = null }) { Text("取消") } })
    state.browse?.let { browse -> AlertDialog(onDismissRequest = model::clearBrowse, title = { Text("选择电脑目录") }, text = {
        Column {
            Text(browse.text("path"), fontSize = 13.sp)
            if (browse.text("parent").isNotBlank()) TextButton(onClick = { model.browse(browse.text("parent")) }, enabled = !state.busy) { Text("上级目录") }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(browse.objects("directories")) { directory -> TextButton(onClick = { model.browse(directory.text("path")) }, enabled = !state.busy) { Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(directory.text("name")) } }
                if (browse.bool("truncated")) item { Text("目录较多，仅显示前 300 项。可在电脑端整理目录或添加项目。", fontSize = 12.sp) }
            }
        }
    }, confirmButton = { TextButton(onClick = { folder = browse.text("path"); model.clearBrowse() }, enabled = !state.busy) { Text("使用此目录") } }, dismissButton = { TextButton(onClick = model::clearBrowse) { Text("取消") } }) }
}

@Composable
fun InboxScreen(state: MobileState, model: PilotViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.approvals.isEmpty() && state.results.isEmpty()) item { EmptyState("暂时没有待处理事项", "审批、任务完成和失败结果会汇总在这里。") }
        if (state.approvals.isNotEmpty()) item { SectionTitle("需要你的确认", "${state.approvals.size} 项") }
        items(state.approvals, key = { "approval-${it.text("callId")}" }) { ApprovalCard(it, state.busy, model, true) }
        if (state.results.isNotEmpty()) item { SectionTitle("任务结果", "${state.results.size} 项") }
        items(state.results, key = { "result-${it.text("id")}" }) { item ->
            PilotPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!item.bool("read")) StatusDot("connected")
                    Text(item.text("title"), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp)); Text(item.text("summary"), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row { TextButton(onClick = { model.result(item) }, enabled = !state.busy) { Text("查看结果") }; TextButton(onClick = { model.openTask(item.text("sessionId")) }, enabled = !state.busy) { Text("打开任务") } }
                if (!item.bool("read")) TextButton(onClick = { model.receipt(item, "read") }, enabled = !state.busy) { Text("标为已读") }
            }
        }
        item { InfoNote("回到 App 会同步最新进度。后台通知尚未启用，离开 App 后系统可能暂停连接。") }
    }
}

@Composable
fun DevicesScreen(state: MobileState, model: PilotViewModel, scan: () -> Unit, paste: () -> Unit) {
    var forget by remember { mutableStateOf<PairedComputer?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SectionTitle("你的电脑", "${state.computers.size} 台") }
        items(state.computers, key = { it.id }) { computer ->
            PilotPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Computer, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(computer.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (computer.id == state.computerId) "当前电脑 · ${state.status}" else "尚未连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (computer.id != state.computerId) TextButton(onClick = { model.switchComputer(computer) }, enabled = !state.busy) { Text("切换") }
                }
                TextButton(onClick = { forget = computer }, enabled = !state.busy) { Text("移除连接", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item { Button(onClick = scan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("扫码添加电脑") } }
        item { OutlinedButton(onClick = paste, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("粘贴配对码") } }
        item { AppearanceSettings() }
        item { SectionTitle("连接说明"); Text("每台电脑的项目、草稿与待核对指令分别保存。断开连接不会停止电脑上的任务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("授权范围：这台电脑上的 Pilot 项目和任务。要撤销手机权限，请在电脑左侧“连接手机 → 已配对设备”中移除。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.computers.firstOrNull { it.id == state.computerId }?.development == true) item { Text("已自动配置公共连接服务，无需自建服务器。连接速度与可用性受网络及公共服务影响。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    forget?.let { computer -> AlertDialog(onDismissRequest = { forget = null }, title = { Text("移除 ${computer.name}？") }, text = { Text("将删除这台电脑在手机上的连接、草稿和待核对记录。电脑上的任务继续运行。") },
        confirmButton = { TextButton(onClick = { model.forget(computer.id); forget = null }) { Text("断开并忘记") } }, dismissButton = { TextButton(onClick = { forget = null }) { Text("取消") } }) }
}

@Composable
fun EmptyState(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}
