package org.korvaihub.app

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = KorvaiDb.get(this)
        val secrets = SecureSession(this)
        setContent { MaterialTheme { KorvaiScreen(database, secrets) } }
    }
}

@Composable
private fun KorvaiScreen(database: KorvaiDb, secrets: SecureSession) {
    val dao = database.dao()
    val scope = rememberCoroutineScope()
    val rows by dao.observe().collectAsState(initial = emptyList())
    val stored = remember { secrets.loadConfig() }
    var bucket by remember { mutableStateOf(stored?.second ?: "korvaibucket") }
    var region by remember { mutableStateOf(stored?.third ?: "us-west-2") }
    var accessKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(stored?.first) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var talamFilter by remember { mutableStateOf("") }
    var nadaiFilter by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Korvai?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { DatabaseBackup.export(context, database, uri); message = "SQLite backup saved successfully" }
            catch (e: Exception) { message = "Backup failed: ${e.message}" }
            finally { busy = false }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val count = DatabaseBackup.restore(context, database, uri)
                selected = null
                message = "Restored $count korvai classifications. Existing unlisted recordings retained."
            } catch (e: Exception) { message = "Restore failed: ${e.message}" }
            finally { busy = false }
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val s = session
            if (s == null) message = "Connect AWS first"
            else {
                busy = true
                scope.launch {
                    try {
                        val key = S3Client(bucket.trim(), region.trim(), s).uploadAudio(context, uri)
                        val row = Korvai(key = key)
                        dao.insertNew(listOf(row))
                        selected = row
                        message = "Uploaded successfully. Add its classification below."
                    } catch (e: Exception) { message = "Upload failed: ${e.message}" }
                    busy = false
                }
            }
        }
    }
    val filtered = rows.filter { r ->
        (!favoritesOnly || r.favorite) && (talamFilter.isBlank() || r.talam.contains(talamFilter, true)) &&
        (nadaiFilter.isBlank() || r.nadai.contains(nadaiFilter, true)) &&
        (search.isBlank() || listOf(r.key, r.tag, r.notes, r.talam, r.nadai, r.kaalam, r.mathirai).any { it.contains(search, true) })
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("🥁 KorvaiHub", style = MaterialTheme.typography.headlineMedium)
        Text("Personal library · Local classifications · S3 audio", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (session == null) {
            Text("Connect AWS S3", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(bucket, { bucket = it }, label = { Text("S3 bucket") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(region, { region = it }, label = { Text("AWS region") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(accessKey, { accessKey = it }, label = { Text("IAM Access Key ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secretKey, { secretKey = it }, label = { Text("IAM Secret Access Key") }, singleLine = true,
                visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(showSecret, { showSecret = it }); Text("Show secret")
            }
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        val candidate = Session(accessKey.trim(), secretKey.trim())
                        require(candidate.access.isNotBlank() && candidate.secret.isNotBlank()) { "Enter both IAM keys" }
                        val count = S3Client(bucket.trim(), region.trim(), candidate).listKeys().size
                        val raw = org.json.JSONObject().put("AccessKeyId", candidate.access).put("SecretAccessKey", candidate.secret).toString()
                        secrets.save(raw, bucket.trim(), region.trim())
                        session = candidate
                        accessKey = ""; secretKey = ""
                        message = "Connection successful: $count audio objects. Credentials saved encrypted."
                    } catch (e: Exception) { message = "Connection failed (nothing saved): ${e.message}" }
                    busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Testing…" else "Test connection & save") }
            Text("Use a dedicated IAM key limited to ListBucket and GetObject on this bucket. Do not use an administrator key.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    val s = session ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            val keys = S3Client(bucket.trim(), region.trim(), s).listKeys()
                            dao.insertNew(keys.map { Korvai(key = it) })
                            message = "Discovered ${keys.size} audio objects. Existing classifications retained."
                        } catch (e: Exception) { message = "Sync failed: ${e.message}" }
                        busy = false
                    }
                }) { Text(if (busy) "Syncing…" else "Sync S3") }
                Button(enabled = !busy, onClick = { uploadPicker.launch("audio/*") }) { Text("Upload audio") }
                OutlinedButton(onClick = { secrets.clear(); session = null; playing = null }) { Text("Disconnect") }
            }
            Text("Connected to $bucket ($region) · IAM credentials encrypted on device", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { backupPicker.launch("korvaihub-backup.sqlite") }) { Text("Backup SQLite") }
            OutlinedButton(enabled = !busy, onClick = { restorePicker.launch(arrayOf("*/*")) }) { Text("Restore SQLite") }
        }
        Text("Backup saves classifications only; recordings remain in S3. Restore merges by S3 key and replaces matching classifications.", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        OutlinedTextField(search, { search = it }, label = { Text("Search korvais") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(talamFilter, { talamFilter = it }, label = { Text("Talam") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(nadaiFilter, { nadaiFilter = it }, label = { Text("Nadai") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(favoritesOnly, { favoritesOnly = it }); Text("Favorites only")
            Spacer(Modifier.weight(1f)); Text("${filtered.size}/${rows.size}")
        }
        if (selected != null) {
            KorvaiEditor(selected!!, onBack = { selected = null }, onSave = { changed ->
                scope.launch { dao.update(changed); selected = null; message = "Classification saved on phone" }
            })
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.key }) { row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.tag.ifBlank { row.key.substringAfterLast('/') }, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(row.talam, row.nadai, row.mathirai.takeIf { it.isNotBlank() }?.let { "$it mathirai" } ?: "").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Unclassified" })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    try {
                                        val s = session ?: error("Connect AWS first")
                                        playing = S3Client(bucket.trim(), region.trim(), s).audioUrl(row.key)
                                    } catch (e: Exception) { message = e.message ?: "Playback unavailable" }
                                }) { Text("▶ Play") }
                                TextButton(onClick = { selected = row }) { Text("Edit") }
                                TextButton(onClick = { scope.launch { dao.update(row.copy(favorite = !row.favorite)) } }) { Text(if (row.favorite) "★" else "☆") }
                            }
                        }
                    }
                }
            }
        }
        if (playing != null) {
            val url = playing!!
            key(url) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
                DisposableEffect(player) { onDispose { player.release() } }
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxWidth().height(90.dp))
            }
            TextButton(onClick = { playing = null }) { Text("Close player") }
        }
    }
}

@Composable
private fun KorvaiEditor(row: Korvai, onBack: () -> Unit, onSave: (Korvai) -> Unit) {
    var item by remember(row.key) { mutableStateOf(row) }
    Text("Edit classification", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().heightIn(max = 370.dp)) {
        LazyColumn {
            item { Text("S3: ${row.key}", style = MaterialTheme.typography.bodySmall) }
            item { Field("Tag", item.tag) { item = item.copy(tag = it) } }
            item { Field("Talam", item.talam) { item = item.copy(talam = it) } }
            item { Field("Kaalam", item.kaalam) { item = item.copy(kaalam = it) } }
            item { Field("Nadai", item.nadai) { item = item.copy(nadai = it) } }
            item { Field("Edam", item.edam) { item = item.copy(edam = it) } }
            item { Field("Akshara", item.akshara) { item = item.copy(akshara = it) } }
            item { Field("Mathirai", item.mathirai) { item = item.copy(mathirai = it) } }
            item { Field("Purvanga mathirai", item.purvangaMathirai) { item = item.copy(purvangaMathirai = it) } }
            item { Field("Madhyanga mathirai", item.madhyangaMathirai) { item = item.copy(madhyangaMathirai = it) } }
            item { Field("Utaranga mathirai", item.utarangaMathirai) { item = item.copy(utarangaMathirai = it) } }
            item { Field("Notes", item.notes) { item = item.copy(notes = it) } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onSave(item) }) { Text("Save") }
        OutlinedButton(onClick = onBack) { Text("Cancel") }
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
