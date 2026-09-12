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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Scaffold(containerColor = Canvas, topBar = {
        Surface(color = Canvas) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(settings.model.ifBlank { "Ollama Mobile" }, fontWeight = FontWeight.SemiBold, color = Ink)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status, style = MaterialTheme.typography.labelSmall, color = if (status == "Terhubung") Color(0xFF35845C) else Color.Gray)
                        if (status == "Terhubung") {
                            Text(" • ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(settings.chatMode.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Accent)
                        }
                    }
                }
                IconButton(onClick = vm::clear) { Icon(Icons.Outlined.Add, "Chat baru") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Pengaturan") }
            }
        }
    }, bottomBar = {
        Surface(color = Color.White, tonalElevation = 1.dp) {
            Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                if (tool.isNotBlank() && settings.chatMode == ChatMode.AGENT) {
                    Text(tool, style = MaterialTheme.typography.labelSmall, color = Accent, modifier = Modifier.padding(4.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Kirim pesan…") }, maxLines = 5, shape = RoundedCornerShape(16.dp))
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { val t=input; input=""; vm.send(t) }, enabled = input.isNotBlank() && !busy && settings.model.isNotBlank(),
                        modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.Send, "Kirim") }
                }
            }
        }
    }) { pad ->
        if (messages.isEmpty()) EmptyState(Modifier.padding(pad)) else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { MessageBubble(it) }
        }
    }
    if (showSettings) SettingsSheet(settings, models, status, onDismiss = { showSettings=false }, onSave = { vm.saveSettings(it); showSettings=false; vm.connect() }, onTest = { vm.connect() })
}

@Composable fun EmptyState(modifier: Modifier = Modifier) = Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE5F2FC), modifier = Modifier.size(72.dp)) { Box(contentAlignment = Alignment.Center) { Text("◉", style = MaterialTheme.typography.headlineLarge, color = Accent) } }
        Spacer(Modifier.height(20.dp)); Text("AI lokal, di tangan Anda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp)); Text("Hubungkan ke gateway Ollama di komputer, pilih model, lalu mulai mengobrol.", color = Color.Gray)
    }
}

@Composable fun MessageBubble(m: ChatMessage) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = if (user) Accent else Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 330.dp)) {
            Text(m.content.ifBlank { "…" }, Modifier.padding(14.dp), color = if (user) Color.White else Ink)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsSheet(initial: AppSettings, models: List<String>, status: String, onDismiss:()->Unit, onSave:(AppSettings)->Unit, onTest:()->Unit) {
    var s by remember(initial) { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Mode Percakapan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = s.chatMode == ChatMode.CHAT, onClick = { s = s.copy(chatMode = ChatMode.CHAT) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Chat") }
                SegmentedButton(selected = s.chatMode == ChatMode.AGENT, onClick = { s = s.copy(chatMode = ChatMode.AGENT) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Agent") }
            }
            Text(
                if (s.chatMode == ChatMode.CHAT) "Percakapan biasa tanpa akses file" else "Model dapat membaca file dan, jika diizinkan, mengedit file",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("Koneksi lokal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Gunakan IP komputer. Contoh: http://192.168.1.10:8765", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            OutlinedTextField(s.gatewayUrl, { s=s.copy(gatewayUrl=it) }, label={Text("Alamat gateway")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            OutlinedTextField(s.token, { s=s.copy(token=it) }, label={Text("Token")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            ExposedDropdownMenuBox(expanded, { expanded=!expanded }) {
                OutlinedTextField(s.model, {}, readOnly=true, label={Text("Model")}, trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)}, modifier=Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded, {expanded=false}) { models.forEach { model -> DropdownMenuItem({Text(model)}, { s=s.copy(model=model); expanded=false }) } }
            }
            
            if (s.chatMode == ChatMode.AGENT) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Switch(s.allowEdits, {s=s.copy(allowEdits=it)})
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Izinkan edit file")
                        Text("Hanya dalam folder sandbox gateway", style=MaterialTheme.typography.labelSmall, color=Color.Gray)
                    }
                }
            }

            Text(status, style=MaterialTheme.typography.labelMedium)
            Button(onClick={onSave(s)}, modifier=Modifier.fillMaxWidth().height(50.dp)) { Text("Simpan & hubungkan") }
            TextButton(onClick=onTest, modifier=Modifier.fillMaxWidth()) { Text("Uji koneksi") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
