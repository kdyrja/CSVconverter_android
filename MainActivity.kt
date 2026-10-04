package com.example.x431tocsv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
            
            var themePref by remember { mutableStateOf(prefs.getInt("theme", 0)) }
            var askForSaveLocation by remember { mutableStateOf(prefs.getBoolean("ask_save", false)) }

            val isDark = when (themePref) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        themePref = themePref,
                        onThemeChange = { 
                            themePref = it
                            prefs.edit().putInt("theme", it).apply()
                        },
                        askForSaveLocation = askForSaveLocation,
                        onAskSaveChange = {
                            askForSaveLocation = it
                            prefs.edit().putBoolean("ask_save", it).apply()
                        }
                    )
                }
            }
        }
    }
}

object T {
    private val isCz = Locale.getDefault().language == "cs"
    val title = if (isCz) "X431 Převodník" else "X431 Converter"
    val ready = if (isCz) "Připraveno. Vyberte .x431 log z diagnostiky." else "Ready. Select .x431 diagnostic log."
    val processing = if (isCz) "Zpracovávám data..." else "Processing data..."
    val success = if (isCz) "Úspěšně uloženo!" else "Saved successfully!"
    val error = if (isCz) "Chyba při zpracování." else "Error during processing."
    val selectLog = if (isCz) "Vybrat X431 log" else "Select X431 log"
    val settings = if (isCz) "Nastavení" else "Settings"
    val askSave = if (isCz) "Vždy se ptát na název a kam uložit" else "Always ask for name and save location"
    val themeSystem = if (isCz) "Podle systému" else "System default"
    val themeLight = if (isCz) "Světlý" else "Light"
    val themeDark = if (isCz) "Tmavý" else "Dark"
    val share = if (isCz) "Sdílet CSV" else "Share CSV"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    themePref: Int, onThemeChange: (Int) -> Unit,
    askForSaveLocation: Boolean, onAskSaveChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var showSettings by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(T.ready) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingInputUri by remember { mutableStateOf<Uri?>(null) }

    val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { outputUri ->
        if (outputUri != null && pendingInputUri != null) {
            isProcessing = true
            statusText = T.processing
            coroutineScope.launch {
                val success = processFileCustom(context, pendingInputUri!!, outputUri)
                isProcessing = false
                if (success) {
                    statusText = T.success
                    lastSavedUri = outputUri
                } else {
                    statusText = T.error
                }
            }
        } else {
            statusText = T.ready
        }
    }

    val openLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { inputUri ->
        if (inputUri != null) {
            lastSavedUri = null
            if (askForSaveLocation) {
                pendingInputUri = inputUri
                // Zkusíme nabídnout původní název i v manuálním ukládání
                val originalName = getOriginalFileName(context, inputUri)
                val defaultName = if (originalName.endsWith(".x431", true)) {
                    originalName.dropLast(5) + ".csv"
                } else {
                    "$originalName.csv"
                }
                createDocLauncher.launch(defaultName)
            } else {
                isProcessing = true
                statusText = T.processing
                coroutineScope.launch {
                    val resultUri = processFileAuto(context, inputUri)
                    isProcessing = false
                    if (resultUri != null) {
                        statusText = "${T.success}\n(Složka: Downloads/csv_output)"
                        lastSavedUri = resultUri
                    } else {
                        statusText = T.error
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) T.settings else T.title) },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Nastavení")
                    }
                }
            )
        }
    ) { padding ->
        if (showSettings) {
            SettingsContent(
                modifier = Modifier.padding(padding),
                themePref = themePref, onThemeChange = onThemeChange,
                askForSaveLocation = askForSaveLocation, onAskSaveChange = onAskSaveChange
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Text(text = statusText, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { openLogLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isProcessing
                ) {
                    Text(T.selectLog)
                }

                if (lastSavedUri != null && !isProcessing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = { shareFile(context, lastSavedUri!!) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(T.share)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsContent(
    modifier: Modifier,
    themePref: Int, onThemeChange: (Int) -> Unit,
    askForSaveLocation: Boolean, onAskSaveChange: (Boolean) -> Unit
) {
    Column(modifier = modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(T.askSave, modifier = Modifier.weight(1f))
            Switch(checked = askForSaveLocation, onCheckedChange = onAskSaveChange)
        }
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Motiv aplikace", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = themePref == 0, onClick = { onThemeChange(0) })
            Text(T.themeSystem)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = themePref == 1, onClick = { onThemeChange(1) })
            Text(T.themeLight)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = themePref == 2, onClick = { onThemeChange(2) })
            Text(T.themeDark)
        }
    }
}

fun shareFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, T.share))
}

// Funkce pro získání původního názvu souboru (např. log_octavia.x431)
fun getOriginalFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = cursor.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "Log_${System.currentTimeMillis()}"
}

suspend fun processFileCustom(context: Context, inputUri: Uri, outputUri: Uri): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                context.contentResolver.openOutputStream(outputUri)?.use { output ->
                    parseX431Data(input, output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

suspend fun processFileAuto(context: Context, inputUri: Uri): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            // Použití původního názvu
            val originalName = getOriginalFileName(context, inputUri)
            val fileName = if (originalName.endsWith(".x431", ignoreCase = true)) {
                originalName.dropLast(5) + ".csv"
            } else if (originalName.contains(".")) {
                originalName.substringBeforeLast(".") + ".csv"
            } else {
                "$originalName.csv"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/csv_output")
            }
            
            val outputUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (outputUri != null) {
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    context.contentResolver.openOutputStream(outputUri)?.use { output ->
                        parseX431Data(input, output)
                    }
                }
            }
            outputUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

fun parseX431Data(input: InputStream, output: OutputStream) {
    val bytes = input.readBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    buffer.position(0x134)
    val columnCount = (buffer.get().toInt() and 0xFF) / 4

    buffer.position(0x0c)
    val var32 = buffer.getInt()
    buffer.position(buffer.position() + var32)

    for (i in 0 until 8) {
        val var16 = buffer.getShort().toInt() and 0xFFFF
        buffer.position(buffer.position() + var16 - 2)
    }

    val pointValues = mutableListOf<String>()
    while (buffer.position() < buffer.capacity()) {
        if (buffer.remaining() < 2) break
        val var16 = buffer.getShort().toInt() and 0xFFFF
        if (buffer.remaining() < var16 - 2) break
        
        if (var16 >= 3) {
            val strBytes = ByteArray(var16 - 3)
            buffer.get(strBytes)
            pointValues.add(String(strBytes, Charsets.UTF_8))
            buffer.get() 
        } else {
            buffer.position(buffer.position() + var16 - 2)
            pointValues.add("")
        }
    }

    val columnNames = Array(columnCount + 1) { "" }
    columnNames[0] = "Num"

    buffer.position(0x138)
    for (i in 0 until columnCount) {
        val index = buffer.getShort().toInt() and 0xFFFF
        buffer.getShort() 
        if (index != 0 && index - 0x09 >= 0 && index - 0x09 < pointValues.size) {
            columnNames[i + 1] = "${i + 1}. ${pointValues[index - 0x09]}"
        }
    }

    for (i in 0 until columnCount) {
        val index = buffer.getShort().toInt() and 0xFFFF
        buffer.getShort() 
        if (index != 0 && index - 0x09 >= 0 && index - 0x09 < pointValues.size) {
            columnNames[i + 1] = "${columnNames[i + 1]} (${pointValues[index - 0x09]})"
        }
    }

    buffer.position(0x11c)
    val offsetVar16 = buffer.getShort().toInt() and 0xFFFF

    buffer.position(offsetVar16 + 8)
    val recordsCount = buffer.getInt()
    buffer.getInt() 

    val totalRows = (recordsCount / 4) / columnCount
    val rows = mutableListOf<Array<String>>()

    for (i in 0 until totalRows) {
        val row = Array(columnCount + 1) { "0" }
        row[0] = (i + 1).toString()
        for (j in 0 until columnCount) {
            val index = (buffer.getShort().toInt() and 0xFFFF) - 0x09
            buffer.getShort() 
            if (index in 0 until pointValues.size) {
                row[j + 1] = pointValues[index]
            }
        }
        rows.add(row)
    }

    val writer = output.bufferedWriter(Charsets.UTF_8)
    fun escapeCsv(value: String): String = if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value

    writer.write(columnNames.joinToString(",") { escapeCsv(it) })
    writer.newLine()

    for (row in rows) {
        writer.write(row.joinToString(",") { escapeCsv(it) })
        writer.newLine()
    }
    writer.flush()
}