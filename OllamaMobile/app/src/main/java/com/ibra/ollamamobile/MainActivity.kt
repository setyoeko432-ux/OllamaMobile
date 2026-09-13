package com.ibra.ollamamobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalClipboardManager
import com.ibra.ollamamobile.ui.MarkdownMessage
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

private val Ink = Color(0xFF242422)
private val Canvas = Color(0xFFF7F7F5)
private val Accent = Color(0xFF2783DE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                    val vm: MainViewModel = viewModel()
                    App(vm)
                }
            }
        }
    }
}

@Composable fun App(vm: MainViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val generationState by vm.generationState.collectAsStateWithLifecycle()
    val tool by vm.toolActivity.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val scanStatuses by vm.scanStatuses.collectAsStateWithLifecycle()
    val pendingEdit by vm.pendingEdit.collectAsStateWithLifecycle()
    val pendingApproval by vm.pendingApproval.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    
    val workspaceFiles by vm.workspaceFiles.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    
    var showSettings by remember { mutableStateOf(false) }
    var showLocalFiles by remember { mutableStateOf(false) }
    var showTelDetails by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0) {
                true
            } else {
                val lastVisibleItem = visibleItemsInfo.lastOrNull()
                lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (isAtBottom && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = Canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(color = Canvas) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(settings.model.ifBlank { "Ollama Mobile" }, fontWeight = FontWeight.SemiBold, color = Ink)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when {
                                status.startsWith("Terhubung") -> Color(0xFF35845C)
                                status.contains("Gagal") || status.contains("tidak valid") -> Color(0xFFD32F2F)
                                else -> Color.Gray
                            }
                            Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
                            
                            if (status.startsWith("Terhubung")) {
                                Text(" • ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(settings.chatMode.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Accent)
                            }
                        }
                    }
                    IconButton(onClick = { 
                        showLocalFiles = true 
                        vm.fetchRoots()
                        vm.fetchWorkspace()
                    }) { Icon(Icons.Outlined.Folder, "Local Files") }
                    IconButton(onClick = vm::clear) { Icon(Icons.Outlined.Add, "Chat baru") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Pengaturan") }
                }
            }
        },
        bottomBar = {
            Surface(color = Color.White, tonalElevation = 1.dp) {
                Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                    if (telemetry.requestId.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Text("Metrik Performa", style = MaterialTheme.typography.labelMedium, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showTelDetails = !showTelDetails }) {
                                Text(if (showTelDetails) "Sembunyikan" else "Lihat Detail", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (showTelDetails) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9F8)), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Context aktif: ${telemetry.selectedNumCtx} token", style = MaterialTheme.typography.labelSmall)
                                    Text("Alasan pemilihan: ${telemetry.selectionReason}", style = MaterialTheme.typography.labelSmall)
                                    Text("Estimasi token input: ${telemetry.estimatedInputTokens}", style = MaterialTheme.typography.labelSmall)
                                    Text("Model status: ${if (telemetry.isWarm) "Warm model" else "Cold start"}", style = MaterialTheme.typography.labelSmall)
                                    if (telemetry.firstTokenAt > 0) {
                                        Text("Time to first token (TTFT): ${telemetry.timeToFirstTokenMs} ms", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (telemetry.completedAt > telemetry.firstTokenAt && telemetry.tokenCount > 0) {
                                        Text(String.format("Kecepatan generasi: %.1f tokens/s", telemetry.tokensPerSecond), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text("Memory pressure host: ${telemetry.memoryPressure}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    if (busy || generationState != GenerationState.IDLE) {
                        val stateLabel = when (generationState) {
                            GenerationState.CONNECTING -> "Menghubungkan ke gateway..."
                            GenerationState.GENERATING -> "Sedang menulis..."
                            GenerationState.RUNNING_TOOL -> tool.ifBlank { "Menjalankan tool..." }
                            GenerationState.WAITING_FOR_APPROVAL -> "Menunggu persetujuan..."
                            GenerationState.APPLYING_EDIT -> "Menerapkan perubahan..."
                            GenerationState.RESUMING_AGENT -> "Melanjutkan agent..."
                            GenerationState.CANCELLED -> "Dibatalkan"
                            GenerationState.FAILED -> "Gagal"
                            GenerationState.COMPLETED -> "Selesai"
                            else -> ""
                        }
                        if (stateLabel.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Accent)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = if (generationState == GenerationState.FAILED) Color.Red else Accent)
                            }
                        }
                    }
                        Row(verticalAlignment = Alignment.Bottom) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Kirim pesan…") },
                                maxLines = 5,
                                shape = RoundedCornerShape(16.dp),
                                enabled = !busy || (messages.lastOrNull()?.isStreaming == false)
                            )
                            Spacer(Modifier.width(8.dp))
                            if (busy) {
                                FilledIconButton(
                                    onClick = { vm.stopGeneration() },
                                    modifier = Modifier.size(52.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFFDE8E8))
                                ) { Icon(Icons.Outlined.Stop, "Hentikan", tint = Color(0xFF9B1C1C)) }
                            } else {
                                FilledIconButton(
                                    onClick = { val t = input; input = ""; vm.send(t) },
                                    enabled = input.isNotBlank() && settings.model.isNotBlank(),
                                    modifier = Modifier.size(52.dp)
                                ) { Icon(Icons.Outlined.Send, "Kirim") }
                            }
                        }
                    }
                }
            }) { pad ->
            if (messages.isEmpty()) EmptyState(Modifier.padding(pad)) else {
                Box(Modifier.fillMaxSize().padding(pad)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                m = msg,
                                onCopy = { txt ->
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(txt))
                                    scope.launch { snackbarHostState.showSnackbar("Jawaban disalin") }
                                },
                                onRegenerate = {
                                    vm.regenerateLastResponse()
                                },
                                onRetry = {
                                    vm.retryLastResponse()
                                }
                            )
                        }
                    }
                    
                    if (!isAtBottom && messages.isNotEmpty()) {
                        Button(
                            onClick = {
                                scope.launch { listState.animateScrollToItem(messages.size - 1) }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Text("Kembali ke bawah", color = Color.White)
                        }
                    }
                }
            }
        }
    if (showSettings) SettingsSheet(settings, models, status, onDismiss = { showSettings=false }, onSave = { vm.saveSettings(it); showSettings=false; vm.connect() }, onTest = vm::testConnection)
    if (showLocalFiles) LocalFilesSheet(
        roots = roots,
        scanStatuses = scanStatuses,
        workspaceFiles = workspaceFiles,
        searchResults = searchResults,
        onDismiss = { showLocalFiles = false },
        onScan = vm::scanRoot,
        onSearch = vm::searchFiles
    )
    
    pendingEdit?.let { edit ->
        EditConfirmationDialog(edit, onApprove = { vm.respondToApproval(true) }, onReject = { vm.respondToApproval(false) })
    }

    pendingApproval?.let { approval ->
        ApprovalDialog(approval, onApprove = { vm.respondToApproval(true) }, onReject = { vm.respondToApproval(false) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ApprovalDialog(approval: PendingApproval, onApprove:()->Unit, onReject:()->Unit) {
    val payload = approval.payload
    val op = approval.operation
    
    AlertDialog(onDismissRequest = {}, confirmButton = {
        Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = if (op == "workspace_edit") Accent else Color(0xFF35845C))) { 
            Text(if (op == "copy_to_workspace") "Salin File" else "Setujui") 
        }
    }, dismissButton = {
        TextButton(onClick = onReject) { Text("Tolak") }
    }, title = { 
        Text(when(op) {
            "read_source_file" -> "Persetujuan Baca File"
            "copy_to_workspace" -> "Salin ke Workspace"
            "workspace_edit" -> "Edit Workspace"
            else -> "Persetujuan Diperlukan"
        })
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sensitivity = payload.optString("sensitivity", "NORMAL")
            if (sensitivity != "NORMAL") {
                Surface(color = Color(0xFFFFF4E5), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = Color(0xFF663C00), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("File Sensitif ($sensitivity)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF663C00), fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Text(payload.optString("reason", "AI memerlukan akses."), style = MaterialTheme.typography.bodyMedium)
            
            when(op) {
                "copy_to_workspace" -> {
                    Text("Sumber: ${payload.optString("source_display")}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text("Tujuan: ${payload.optString("destination_relative")}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text("Ukuran: ${payload.optLong("size") / 1024} KB", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                "workspace_edit" -> {
                    Text("File: ${payload.optString("path")}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    // Show diff if available
                    val diff = payload.optString("diff")
                    if (diff.isNotBlank()) {
                        Surface(color = Color(0xFFF7F7F7), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Color.LightGray)) {
                            Text(diff, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(4.dp).heightIn(max = 100.dp).verticalScroll(rememberScrollState()))
                        }
                    }
                }
            }
            
            Text("Operasi ini dilakukan secara lokal di laptop Anda.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    })
}

@Composable fun EmptyState(modifier: Modifier = Modifier) = Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE5F2FC), modifier = Modifier.size(72.dp)) { Box(contentAlignment = Alignment.Center) { Text("◉", style = MaterialTheme.typography.headlineLarge, color = Accent) } }
        Spacer(Modifier.height(20.dp)); Text("AI lokal, di tangan Anda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp)); Text("Akses folder laptop Anda dengan aman melalui approved roots.", color = Color.Gray)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun MessageBubble(
    m: ChatMessage,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit
) {
    val user = m.role == "user"
    val systemOrTool = m.role == "system" || m.role == "tool"
    
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        val bubbleModifier = if (user) {
            Modifier.fillMaxWidth(0.75f)
        } else {
            Modifier.fillMaxWidth(0.95f)
        }
        
        Column(bubbleModifier) {
            var showActions by remember { mutableStateOf(false) }
            
            Surface(
                color = if (user) Accent else if (systemOrTool) Color(0xFFF0F0F0) else Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onLongClick = { showActions = true },
                    onClick = {}
                ),
                border = if (!user && !systemOrTool) BorderStroke(1.dp, Color(0xFFE6E5E3)) else null
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (user) {
                        Text(m.content.ifBlank { "…" }, color = Color.White, fontSize = 16.sp)
                    } else {
                        if (m.isStreaming && m.content.isBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                                Spacer(Modifier.width(8.dp))
                                Text("AI sedang berpikir…", fontSize = 16.sp, color = Color.Gray)
                            }
                        } else {
                            MarkdownMessage(
                                markdown = m.content.ifBlank { "…" },
                                isStreaming = m.isStreaming
                            )
                        }
                    }
                    
                    if (m.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFFFDE8E8),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("Error: ${m.error}", color = Color(0xFF9B1C1C), fontSize = 14.sp)
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = onRetry, modifier = Modifier.heightIn(max = 32.dp)) {
                                    Text("Coba lagi", color = Accent, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
            
            if (showActions && !user && !systemOrTool) {
                Row(
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                ) {
                    TextButton(onClick = { onCopy(m.content); showActions = false }) {
                        Text("Salin", fontSize = 12.sp, color = Accent)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onRegenerate(); showActions = false }) {
                        Text("Regenerate", fontSize = 12.sp, color = Accent)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LocalFilesSheet(
    roots: List<FileRoot>,
    scanStatuses: Map<String, String>,
    workspaceFiles: List<JSONObject>,
    searchResults: List<JSONObject>,
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
    onSearch: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.9f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Akses File Lokal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            TabRow(selectedTabIndex = activeTab, containerColor = Color.Transparent) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) { Text("Discovery", modifier = Modifier.padding(12.dp)) }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) { Text("Search", modifier = Modifier.padding(12.dp)) }
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) { Text("Workspace", modifier = Modifier.padding(12.dp)) }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Box(Modifier.weight(1f)) {
                when (activeTab) {
                    0 -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(roots) { root ->
                                val status = scanStatuses[root.name] ?: "idle"
                                Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFF0F0F0))) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(root.name, fontWeight = FontWeight.Bold)
                                            Text("Status: $status", style = MaterialTheme.typography.labelSmall)
                                        }
                                        IconButton(onClick = { onScan(root.name) }) {
                                            if (status == "running") CircularProgressIndicator(Modifier.size(20.dp))
                                            else Icon(Icons.Outlined.Refresh, null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        Column {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it; onSearch(it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Cari file...") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(searchResults) { file ->
                                    val sensitivity = file.optString("sensitivity", "NORMAL")
                                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFEEEEEE))) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(file.optString("filename"), fontWeight = FontWeight.SemiBold)
                                            Text(file.optString("relative_path"), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                val color = when(sensitivity) {
                                                    "NORMAL" -> Color(0xFF35845C)
                                                    "BLOCKED_SYSTEM" -> Color.Red
                                                    else -> Color(0xFFE67E22)
                                                }
                                                Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp)) {
                                                    Text(sensitivity, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(workspaceFiles) { file ->
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9F9))) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Description, null, tint = Accent)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(file.optString("name"), fontWeight = FontWeight.Medium)
                                            Text("${file.optLong("size") / 1024} KB", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditConfirmationDialog(edit: PendingEdit, onApprove:()->Unit, onReject:()->Unit) {
    AlertDialog(onDismissRequest = {}, confirmButton = {
        Button(
            onClick = onApprove, 
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("Setujui perubahan") }
    }, dismissButton = {
        TextButton(onClick = onReject, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tolak") }
    }, title = { Text("AI ingin mengedit file") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diff perubahan:", fontWeight = FontWeight.SemiBold)
            Surface(
                color = Color(0xFFF7F7F7),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFE6E5E3)),
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
            ) {
                Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Column(Modifier.padding(12.dp)) {
                        edit.diff.split("\n").forEach { line ->
                            val color = when {
                                line.startsWith("+") -> Color(0xFF2D7D46)
                                line.startsWith("-") -> Color(0xFFD32F2F)
                                line.startsWith("@@") -> Color(0xFF6A6A6A)
                                else -> Ink
                            }
                            val bgColor = when {
                                line.startsWith("+") -> Color(0xFFE6F4EA)
                                line.startsWith("-") -> Color(0xFFFDE8E8)
                                else -> Color.Transparent
                            }
                            Text(
                                text = line,
                                color = color,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp,
                                modifier = Modifier.fillMaxWidth().background(bgColor)
                            )
                        }
                    }
                }
            }
            Text("Backup bertimestamp (.bak) akan dibuat secara otomatis.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsSheet(initial: AppSettings, models: List<String>, status: String, onDismiss:()->Unit, onSave:(AppSettings)->Unit, onTest:(AppSettings)->Unit) {
    var s by remember(initial) { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var perfExpanded by remember { mutableStateOf(false) }
    var ctxExpanded by remember { mutableStateOf(false) }
    var threadExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(s.chatMode) {
        if (s.chatMode == ChatMode.CHAT) {
            s = s.copy(allowEdits = false)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Mode Percakapan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = s.chatMode == ChatMode.CHAT, onClick = { s = s.copy(chatMode = ChatMode.CHAT) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Chat") }
                SegmentedButton(selected = s.chatMode == ChatMode.AGENT, onClick = { s = s.copy(chatMode = ChatMode.AGENT) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Agent") }
            }
            Text(
                if (s.chatMode == ChatMode.CHAT) "Percakapan biasa tanpa akses file" else "Model dapat menelusuri folder dan mengedit file yang diizinkan",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("Koneksi gateway", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(s.gatewayUrl, { s=s.copy(gatewayUrl=it) }, label={Text("Alamat (http://IP:PORT)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            
            OutlinedTextField(
                value = s.token,
                onValueChange = { s = s.copy(token = it) },
                label = { Text("Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                    }
                }
            )

            ExposedDropdownMenuBox(expanded, { expanded=!expanded }) {
                OutlinedTextField(s.model, {}, readOnly=true, label={Text("Model")}, trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)}, modifier=Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded, {expanded=false}) { models.forEach { model -> DropdownMenuItem({Text(model)}, { s=s.copy(model=model); expanded=false }) } }
            }
            
            if (s.chatMode == ChatMode.AGENT) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Switch(s.allowEdits, {s=s.copy(allowEdits=it)})
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Izinkan model mengedit file")
                        Text("Konfirmasi manual tetap diperlukan", style=MaterialTheme.typography.labelSmall, color=Color.Gray)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Pengaturan Performa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            ExposedDropdownMenuBox(perfExpanded, { perfExpanded=!perfExpanded }) {
                OutlinedTextField(s.performanceMode, {}, readOnly=true, label={Text("Performance Mode")}, trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(perfExpanded)}, modifier=Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(perfExpanded, {perfExpanded=false}) {
                    listOf("AUTO", "FAST", "BALANCED", "LONG_CONTEXT", "CUSTOM").forEach { mode ->
                        DropdownMenuItem({Text(mode)}, { s=s.copy(performanceMode=mode); perfExpanded=false })
                    }
                }
            }

            ExposedDropdownMenuBox(ctxExpanded, { ctxExpanded=!ctxExpanded }) {
                val displayText = if (s.contextOverride == 0) "Auto" else s.contextOverride.toString()
                OutlinedTextField(displayText, {}, readOnly=true, label={Text("Context Window")}, trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(ctxExpanded)}, modifier=Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(ctxExpanded, {ctxExpanded=false}) {
                    listOf(0, 2048, 4048, 8192).forEach { ctxVal ->
                        val itemLabel = if (ctxVal == 0) "Auto" else ctxVal.toString()
                        DropdownMenuItem({Text(itemLabel)}, { s=s.copy(contextOverride=ctxVal); ctxExpanded=false })
                    }
                }
            }

            OutlinedTextField(
                value = if (s.outputTokenLimit == 0) "" else s.outputTokenLimit.toString(),
                onValueChange = { s = s.copy(outputTokenLimit = it.toIntOrNull() ?: 0) },
                label = { Text("Output Token Limit (0 untuk default)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenuBox(threadExpanded, { threadExpanded=!threadExpanded }) {
                OutlinedTextField(s.threadMode, {}, readOnly=true, label={Text("Thread CPU Mode")}, trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(threadExpanded)}, modifier=Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(threadExpanded, {threadExpanded=false}) {
                    listOf("AUTO", "6", "8", "10", "12").forEach { tMode ->
                        DropdownMenuItem({Text(tMode)}, { s=s.copy(threadMode=tMode); threadExpanded=false })
                    }
                }
            }

            OutlinedTextField(
                value = s.keepAliveDuration,
                onValueChange = { s = s.copy(keepAliveDuration = it) },
                label = { Text("Keep Model Loaded (e.g., 5m, 15m, 30m, 0)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text(status, style=MaterialTheme.typography.labelMedium, color = if (status.contains("Gagal") || status.contains("tidak valid")) Color(0xFFD32F2F) else Color.Unspecified)
            Button(onClick={onSave(s)}, modifier=Modifier.fillMaxWidth().height(50.dp)) { Text("Simpan & Hubungkan") }
            TextButton(onClick={onTest(s)}, modifier=Modifier.fillMaxWidth()) { Text("Uji Koneksi") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
