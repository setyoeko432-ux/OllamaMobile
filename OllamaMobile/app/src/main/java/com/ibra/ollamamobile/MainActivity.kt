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

private val Ink = Color(0xFF242422)
private val Canvas = Color(0xFFF7F7F5)
private val Accent = Color(0xFF2783DE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas)) { App() } }
    }
}

@Composable fun App(vm: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val tool by vm.toolActivity.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val scanStatuses by vm.scanStatuses.collectAsStateWithLifecycle()
    val pendingEdit by vm.pendingEdit.collectAsStateWithLifecycle()
    
    var showSettings by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
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
                            Text(status, style = MaterialTheme.typography.labelSmall, color = if (status.startsWith("Terhubung")) Color(0xFF35845C) else Color.Gray)
                            if (status.startsWith("Terhubung")) {
                                Text(" • ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(settings.chatMode.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Accent)
                            }
                        }
                    }
                    IconButton(onClick = { showFolders = true }) { Icon(Icons.Outlined.Folder, "Folder AI") }
                        IconButton(onClick = vm::clear) { Icon(Icons.Outlined.Add, "Chat baru") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Pengaturan") }
                    }
                }
            },
            bottomBar = {
                Surface(color = Color.White, tonalElevation = 1.dp) {
                    Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                        if (tool.isNotBlank() && settings.chatMode == ChatMode.AGENT) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Accent)
                                Spacer(Modifier.width(8.dp))
                                Text(tool, style = MaterialTheme.typography.labelSmall, color = Accent)
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
    if (showSettings) SettingsSheet(settings, models, status, onDismiss = { showSettings=false }, onSave = { vm.saveSettings(it); showSettings=false; vm.connect() }, onTest = { vm.connect() })
    if (showFolders) FoldersSheet(roots, scanStatuses, onDismiss = { showFolders=false }, onScan = vm::scanRoot)
    
    pendingEdit?.let { edit ->
        EditConfirmationDialog(edit, onApprove = { vm.respondToEdit(true) }, onReject = { vm.respondToEdit(false) })
    }
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
@Composable fun FoldersSheet(roots: List<FileRoot>, statuses: Map<String, String>, onDismiss:()->Unit, onScan:(String)->Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Folder AI (Approved Roots)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (roots.isEmpty()) {
                Text("Belum ada folder yang dikonfigurasi di gateway.", color = Color.Gray)
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            ) {
                items(roots) { root ->
                    val status = statuses[root.name] ?: "idle"
                    val isRunning = status == "running"
                    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFF0F0F0))) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(root.name, fontWeight = FontWeight.SemiBold)
                                Text(root.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    Icon(
                                        if (root.access == "read-only") Icons.Outlined.Lock else Icons.Outlined.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = Accent
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(root.access, style = MaterialTheme.typography.labelSmall, color = Accent)
                                }
                                Text("Status: $status", style = MaterialTheme.typography.labelSmall, color = if (isRunning) Accent else Color.DarkGray, fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal)
                            }
                            IconButton(onClick = { onScan(root.name) }, enabled = !isRunning) { 
                                if (isRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Refresh, "Scan")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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

            Text(status, style=MaterialTheme.typography.labelMedium, color = if (status.contains("Gagal") || status.contains("tidak valid")) Color(0xFFD32F2F) else Color.Unspecified)
            Button(onClick={onSave(s)}, modifier=Modifier.fillMaxWidth().height(50.dp)) { Text("Simpan & Hubungkan") }
            TextButton(onClick={onTest(s)}, modifier=Modifier.fillMaxWidth()) { Text("Uji Koneksi") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
