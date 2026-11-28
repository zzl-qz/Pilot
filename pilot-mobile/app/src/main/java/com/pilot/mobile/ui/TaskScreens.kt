package com.pilot.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilot.mobile.DetailKind
import com.pilot.mobile.MobileState
import com.pilot.mobile.PilotViewModel
import com.pilot.mobile.transport.Protocol
import com.pilot.mobile.transport.bool
import com.pilot.mobile.transport.long
import com.pilot.mobile.transport.objects
import com.pilot.mobile.transport.text
import kotlinx.serialization.json.*

@Composable
fun TaskDetail(state: MobileState, model: PilotViewModel) {
    val canAct = !state.busy && state.pending == null
    val list = rememberLazyListState()
    var expandedActivity by rememberSaveable(state.selected?.text("id")) { mutableStateOf(false) }
    var editQueue by remember { mutableStateOf(-1) }
    var queueText by rememberSaveable { mutableStateOf("") }
    var removeQueue by remember { mutableStateOf(-1) }
    var editingRevision by remember { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    val approvals = state.approvals.filter { it.text("sessionId") == state.selected?.text("id") }
    val activityKeys = state.rows.filter { it.role == "activity" }.map { it.key }
    val visibleActivity = if (expandedActivity) activityKeys.toSet() else activityKeys.takeLast(6).toSet()
    val rows = state.rows.filter { it.role != "activity" || it.key in visibleActivity }
    val nearBottom by remember { derivedStateOf { !list.canScrollForward || (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 } }
    LaunchedEffect(rows.size, rows.lastOrNull()?.text?.length) {
        if (nearBottom && rows.isNotEmpty() && !state.viewingHistory) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (state.runId.isNotBlank()) "RUNNING" else state.selected?.text("status").orEmpty())
            Text(state.phase.ifBlank { "等待任务" }, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.runId.isNotBlank()) TextButton(onClick = { model.control("pause") }, enabled = canAct) { Text("暂停") }
            else if (state.selected?.text("status") in setOf("PAUSED", "INTERRUPTED", "CANCELLED", "FAILED")) TextButton(onClick = { model.control("continue") }, enabled = canAct) { Text("继续") }
            Box {
                TextButton(onClick = { showTools = true }, enabled = !state.busy) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("资料") }
                DropdownMenu(showTools, onDismissRequest = { showTools = false }) {
                    listOf("成果" to "products", "改动" to "diff", "用量" to "context").forEach { (title, kind) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { showTools = false; model.inspect(kind) })
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        VerdictBadge(state.validation, Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth())
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(approvals, key = { "approval-${it.text("callId")}" }) { ApprovalCard(it, !canAct, model, false) }
            if (activityKeys.size > visibleActivity.size || expandedActivity) item {
                TextButton(onClick = { expandedActivity = !expandedActivity }) {
                    Icon(if (expandedActivity) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (expandedActivity) "收起更早的执行过程" else "更早的执行过程 · ${activityKeys.size - visibleActivity.size}")
                }
            }
            if (state.viewingHistory) item { Row { Text("正在查看历史", Modifier.weight(1f).padding(top = 12.dp), fontSize = 13.sp); TextButton(onClick = model::latest, enabled = !state.busy) { Text("返回最新记录") } } }
            if (state.limitedHistory) item { TextButton(onClick = model::older, enabled = !state.busy) { Text("加载更早记录") } }
            items(rows, key = { it.key }) { row ->
                if (row.role == "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(Modifier.padding(start = 24.dp), shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        SelectionContainer { Text(row.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge) }
                    }
                } else if (row.role == "thinking") ThinkingRow(row.text)
                else if (row.role == "activity") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(row.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PilotMark(Modifier.size(18.dp)); Text("Pilot", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MessageBody(row.text)
                }
            }
            if (state.queued.isNotEmpty()) item { Text("待发送 · ${state.queued.size}", style = MaterialTheme.typography.labelLarge) }
            items(state.queued.size) { index ->
                PilotPanel(Modifier.fillMaxWidth()) {
                    Text(state.queued[index], maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { model.steer(index) }, enabled = state.runId.isNotBlank() && canAct) { Text("引导") }
                        TextButton(onClick = { editQueue = index; queueText = state.queued[index]; editingRevision = state.queueRevision }, enabled = canAct) { Text("编辑") }
                        if (index > 0) TextButton(onClick = { model.queue(index, "up") }, enabled = canAct) { Text("上移") }
                        IconButton(onClick = { removeQueue = index; editingRevision = state.queueRevision }, enabled = canAct) { Icon(Icons.Outlined.DeleteOutline, "删除队列第 ${index + 1} 条") }
                    }
                }
            }
            if (rows.isEmpty()) item {
                if (state.runId.isNotBlank()) EmptyState("正在处理任务", "思考和最近的工具调用会直接出现在这里。")
                else EmptyState("准备开始", "描述希望完成的任务，执行进度和回复会显示在这里。")
            }
        }
    }
    if (editQueue >= 0) TextInputDialog(
        title = "编辑待发送内容", value = queueText, onValueChange = { queueText = it }, maxLength = 32_000, maxLines = 8,
        onConfirm = { model.queue(editQueue, "edit", queueText, editingRevision); editQueue = -1 },
        onDismiss = { editQueue = -1 },
    )
    if (removeQueue >= 0) AlertDialog(onDismissRequest = { removeQueue = -1 }, title = { Text("删除这条待发送内容？") }, text = { Text(state.queued.getOrNull(removeQueue).orEmpty(), maxLines = 5) },
        confirmButton = { TextButton(onClick = { model.queue(removeQueue, "remove", expectedRevision = editingRevision); removeQueue = -1 }) { Text("删除") } }, dismissButton = { TextButton(onClick = { removeQueue = -1 }) { Text("取消") } })
}

@Composable
private fun ThinkingRow(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text(if (open) "收起思考" else "正在思考")
        }
        SelectionContainer {
            Text(text, maxLines = if (open) 12 else 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(approval: JsonObject, busy: Boolean, model: PilotViewModel, global: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val known = approval.text("sessionId").isNotBlank()
    PilotPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Security, null, Modifier.size(18.dp), tint = statusColor("WAITING_APPROVAL"))
            Text(if (global) approval.text("title") else "需要确认", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(approval.text("toolName"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(approval.text("argsSummary"), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { confirm = true }, enabled = !busy && known, modifier = Modifier.heightIn(min = 48.dp)) { Text("允许一次") }
            OutlinedButton(onClick = { model.approve(approval.text("callId"), false) }, enabled = !busy && known, modifier = Modifier.heightIn(min = 48.dp)) { Text("拒绝") }
            if (global) TextButton(onClick = { model.openTask(approval.text("sessionId")) }, enabled = !busy && known) { Text("打开任务") }
        }
        if (!known) Text("任务归属尚未确认，正在同步。", fontSize = 12.sp)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("允许 ${approval.text("title")} 执行？") }, text = {
        SelectionContainer { Text(approval.text("argsSummary"), Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), fontSize = 13.sp) }
    }, confirmButton = { TextButton(onClick = { confirm = false; model.approve(approval.text("callId"), true) }, enabled = !busy) { Text("确认允许") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
fun Composer(state: MobileState, model: PilotViewModel, onAttach: () -> Unit, onVoice: () -> Unit) {
    val canAct = !state.busy && state.pending == null
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(8.dp)) {
        if (state.attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.attachments.forEachIndexed { index, attachment -> InputChip(selected = false, onClick = { model.removeAttachment(index) }, enabled = !state.busy,
                label = { Text("${attachment.name} · ${attachment.bytes / 1024} KB", maxLines = 1) }, trailingIcon = { Icon(Icons.Outlined.Close, "移除 ${attachment.name}", Modifier.size(16.dp)) }) }
        }
        TextField(value = state.draft, onValueChange = model::draft,
            modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).semantics { contentDescription = "描述任务或补充要求" },
            maxLines = 4, textStyle = MaterialTheme.typography.bodyLarge, placeholder = { Text("描述任务或补充要求", style = MaterialTheme.typography.bodyLarge) },
            colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAttach, enabled = !state.busy) { Icon(Icons.Outlined.AttachFile, "添加附件") }
            IconButton(onClick = onVoice, enabled = !state.busy) { Icon(Icons.Outlined.MicNone, "语音输入") }
            if (state.runId.isNotBlank()) Text("发送后排队", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Spacer(Modifier.weight(1f))
            if (state.draft.isBlank() && state.attachments.isEmpty() && state.runId.isNotBlank()) FilledTonalButton(onClick = { model.control("cancel") }, enabled = canAct, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("停止") }
            else Button(onClick = model::send, enabled = (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && canAct, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("发送") }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(state: MobileState, model: PilotViewModel) {
    val data = state.detail ?: return
    var downloadProduct by rememberSaveable { mutableStateOf("") }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && downloadProduct.isNotBlank()) model.download(Protocol.json.parseToJsonElement(downloadProduct).jsonObject, uri)
        downloadProduct = ""
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    ModalBottomSheet(onDismissRequest = model::closeDetail, modifier = Modifier.testTag("detail-sheet"), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 840.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            MatchDialogSystemBars()
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(state.detailTitle, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2); IconButton(onClick = model::closeDetail) { Icon(Icons.Outlined.Close, "关闭详情") } }
                HorizontalDivider()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    when {
                        state.detailKind == DetailKind.PRODUCTS -> {
                            val items = data.objects("items")
                            if (items.isEmpty()) item { Text("此任务还没有可下载的成果。") }
                            items(items) { product ->
                                val bytes = product.long("bytes") ?: 0
                                Column {
                                    Text(product.text("title"), fontWeight = FontWeight.Medium); Text(product.text("summary"), fontSize = 13.sp)
                                    Row {
                                        TextButton(onClick = { model.previewProduct(product) }, enabled = !state.busy) { Text("预览") }
                                        TextButton(onClick = { downloadProduct = product.toString(); saveFile.launch(product.text("filename")) }, enabled = !state.busy && bytes <= Protocol.MAX_RESPONSE) { Text("保存到手机") }
                                    }
                                    if (bytes > Protocol.MAX_RESPONSE) Text("超过 16 MB，请在电脑端下载。", fontSize = 12.sp)
                                }
                            }
                        }
                        state.detailKind == DetailKind.CONTEXT -> {
                            item { Text("${data.text("usedTokens")} / ${data.text("limitTokens")} Tokens · ${data.text("percent")}%") }
                            item { LinearProgressIndicator(progress = { (data.text("percent").toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f }, modifier = Modifier.fillMaxWidth()); Text(if (data.bool("estimated")) "上下文组成是估算值" else "模型上下文用量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(data.objects("segments")) { segment -> Row { Text(mapOf("system" to "系统提示", "user" to "用户消息", "assistant" to "助手消息", "tool" to "工具结果")[segment.text("role")] ?: segment.text("role"), Modifier.weight(1f)); Text("${segment.text("messages")} 条 · ${segment.text("chars")} 字符") } }
                            data["usage"]?.let { usage -> item { ReadableValue(usage) } }
                        }
                        state.detailKind == DetailKind.WORKSPACE_DIFF || state.detailKind == DetailKind.FILE_DIFF || data.containsKey("diff") -> {
                            if (state.detailKind == DetailKind.WORKSPACE_DIFF) item { Text("此处包含工作区中的全部未提交改动，可能来自其他任务。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                            if (data.text("error").isNotBlank()) item { Text(data.text("error"), color = MaterialTheme.colorScheme.error) }
                            items(data.objects("files")) { file -> TextButton(onClick = { model.fileDiff(file.text("path")) }, enabled = !state.busy) { Text("${file.text("path")}  +${file.text("added")} −${file.text("removed")}") } }
                            item { SelectionContainer { Text(data.text("diff").ifBlank { "暂无文本差异" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.horizontalScroll(rememberScrollState())) } }
                            if (data.bool("truncated")) item { Text("差异较长，当前内容已截断。请选择单个文件继续查看。", fontSize = 12.sp) }
                        }
                        else -> item { ReadableValue(data) }
                    }
                }
            }
    }
}

/** 有界详情视图，隐藏内部标识，正文可选择复制；结果格式扩展时仍保留可阅读内容。 */
@Composable
private fun ReadableValue(value: JsonElement, depth: Int = 0) {
    if (depth > 5 || value is JsonNull) return
    val labels = mapOf("summary" to "摘要", "title" to "标题", "state" to "状态", "changedFiles" to "修改文件", "checks" to "验证", "openSteps" to "未完成步骤", "content" to "正文", "text" to "正文", "research" to "调研", "verification" to "验证", "acceptance" to "验收", "verificationDetail" to "验证详情", "inputTokens" to "输入 Tokens", "outputTokens" to "输出 Tokens", "cachedInputTokens" to "缓存命中 Tokens", "cacheHitRate" to "缓存命中率", "cost" to "费用", "estimatedCost" to "费用估算", "currency" to "币种", "model" to "模型", "requestCount" to "请求数", "pricedRequests" to "计价请求")
    when (value) {
        is JsonObject -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            value.entries.filter { (key, entry) -> key !in setOf("id", "revision", "sessionId", "runId", "scheduleId", "workspace", "read", "handled", "at", "createdAt", "updatedAt") && entry !is JsonNull }.forEach { (key, entry) ->
                if (entry is JsonPrimitive) SelectionContainer { Text("${labels[key] ?: key}：${entry.content}", fontSize = 14.sp, lineHeight = 22.sp) }
                else { if (key in labels) Text(labels.getValue(key), fontWeight = FontWeight.Medium); ReadableValue(entry, depth + 1) }
            }
        }
        is JsonArray -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { if (value.isEmpty()) Text("暂无内容", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); value.take(200).forEach { ReadableValue(it, depth + 1) }; if (value.size > 200) Text("内容较多，仅展示前 200 项。") }
        is JsonPrimitive -> SelectionContainer { Text(value.content, fontSize = 14.sp, lineHeight = 23.sp) }
    }
}
