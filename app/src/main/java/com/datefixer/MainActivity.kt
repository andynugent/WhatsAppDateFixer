package com.datefixer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

// ── Colour tokens ────────────────────────────────────────────────────────────
private val BgDeep        = Color(0xFF0D1117)
private val BgCard        = Color(0xFF161B22)
private val BgElevated    = Color(0xFF21262D)
private val AccentGreen   = Color(0xFF3FB950)
private val AccentBlue    = Color(0xFF58A6FF)
private val AccentAmber   = Color(0xFFD29922)
private val AccentRed     = Color(0xFFF85149)
private val TextPrimary   = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val TextMuted     = Color(0xFF484F58)

// ── Data ─────────────────────────────────────────────────────────────────────
data class LogEntry(val message: String, val type: LogType)
enum class LogType { INFO, SUCCESS, ERROR, WARN }

data class Stats(
    val total: Int = 0,
    val fixed: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val current: String = ""
)

// ── WhatsApp filename date patterns ──────────────────────────────────────────
// e.g. IMG-20230415-WA0001.jpg  /  VID-20191231-WA0002.mp4
//      WhatsApp Image 2022-06-14 at 12.30.45.jpeg
//      IMG_20210805_134523.jpg   (general camera-style fallback)
private val PATTERNS: List<Pair<Pattern, String>> = listOf(
    // IMG-20230415-WA0001
    Pattern.compile("""(?:IMG|VID|AUD|PTT)-(\d{8})-WA\d+""") to "yyyyMMdd",
    // WhatsApp Image 2022-06-14 at 12.30.45
    Pattern.compile("""WhatsApp (?:Image|Video|Audio) (\d{4}-\d{2}-\d{2}) at (\d{2}\.\d{2}\.\d{2})""") to "yyyy-MM-dd HH.mm.ss",
    // IMG_20210805_134523
    Pattern.compile("""(?:IMG|VID|PANO|BURST)_(\d{8})_(\d{6})""") to "yyyyMMdd HHmmss",
    // 2023-04-15_123456 or 20230415_123456
    Pattern.compile("""(\d{4}-?\d{2}-?\d{2})_(\d{6})""") to "yyyyMMdd HHmmss",
    // Plain 8-digit date anywhere in name
    Pattern.compile("""(\d{8})""") to "yyyyMMdd"
)

fun extractDateFromFilename(name: String): Long? {
    for ((pattern, fmt) in PATTERNS) {
        val m = pattern.matcher(name)
        if (!m.find()) continue
        try {
            val raw = when (m.groupCount()) {
                1 -> m.group(1)!!
                else -> "${m.group(1)} ${m.group(2)}"
            }.replace("-", "")
            val sdf = SimpleDateFormat(fmt.replace("-", ""), Locale.US)
            sdf.isLenient = false
            val date = sdf.parse(raw) ?: continue
            val ms = date.time
            // Sanity-check: between 2000-01-01 and now+1day
            if (ms in 946684800000L..System.currentTimeMillis() + 86400000L) return ms
        } catch (_: Exception) {}
    }
    return null
}

// ── MainActivity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private val logs       = mutableStateListOf<LogEntry>()
    private val stats      = mutableStateOf(Stats())
    private val running    = mutableStateOf(false)
    private val hasPerms   = mutableStateOf(false)
    private var job: Job?  = null

    // Permission launchers
    private val legacyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPerms.value = results.values.all { it }; if (hasPerms.value) checkAndStart() }

    private val manageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        setContent { AppUI() }
    }

    private fun checkPermissions() {
        hasPerms.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"))
            manageLauncher.launch(intent)
        } else {
            legacyLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }

    private fun checkAndStart() { if (hasPerms.value) startFix() }

    private fun startFix() {
        if (running.value) return
        logs.clear()
        stats.value = Stats()
        running.value = true
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                fixWhatsAppDates()
            } finally {
                withContext(Dispatchers.Main) { running.value = false }
            }
        }
    }

    private fun stopFix() {
        job?.cancel()
        running.value = false
        addLog("⏹ Stopped by user", LogType.WARN)
    }

    // ── Core logic ────────────────────────────────────────────────────────────
    private suspend fun fixWhatsAppDates() {
        addLog("🔍 Scanning WhatsApp media folders…", LogType.INFO)

        val roots = listOf(
            Environment.getExternalStorageDirectory()
        )

        val waDirs = mutableListOf<File>()
        for (root in roots) {
            // Standard WhatsApp paths
            listOf(
                "WhatsApp/Media/WhatsApp Images",
                "WhatsApp/Media/WhatsApp Video",
                "WhatsApp/Media/WhatsApp Audio",
                "WhatsApp/Media/WhatsApp Animated Gifs",
                "WhatsApp/Media/WhatsApp Documents",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
                "DCIM/WhatsApp",
                "Pictures/WhatsApp Images",
            ).map { File(root, it) }.filter { it.exists() && it.isDirectory }.forEach { waDirs += it }
        }

        if (waDirs.isEmpty()) {
            addLog("⚠ No WhatsApp media folders found. Checked standard locations.", LogType.WARN)
            addLog("Looked in: /sdcard/WhatsApp/Media/… and /sdcard/Android/media/com.whatsapp/…", LogType.INFO)
            return
        }

        addLog("📁 Found ${waDirs.size} folder(s):", LogType.INFO)
        waDirs.forEach { addLog("   ${it.absolutePath}", LogType.INFO) }

        var total = 0; var fixed = 0; var skipped = 0; var errors = 0

        for (dir in waDirs) {
            val files = dir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith('.') }
                .toList()

            addLog("\n📂 ${dir.name} — ${files.size} file(s)", LogType.INFO)

            for (file in files) {
                if (!isActive) return
                total++
                updateStats(Stats(total, fixed, skipped, errors, file.name))

                val dateMs = extractDateFromFilename(file.nameWithoutExtension)
                if (dateMs == null) {
                    addLog("⏭ ${file.name} — no date found in filename", LogType.WARN)
                    skipped++; continue
                }

                try {
                    val changed = setFileDates(file, dateMs)
                    if (changed) {
                        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        addLog("✓ ${file.name} → ${fmt.format(Date(dateMs))}", LogType.SUCCESS)
                        fixed++
                    } else {
                        addLog("≈ ${file.name} — already correct", LogType.INFO)
                        skipped++
                    }
                } catch (e: Exception) {
                    addLog("✗ ${file.name} — ${e.message}", LogType.ERROR)
                    errors++
                }
                updateStats(Stats(total, fixed, skipped, errors, file.name))
            }
        }

        // Notify MediaStore so Gallery picks up the changes
        addLog("\n🔄 Notifying media scanner…", LogType.INFO)
        notifyMediaStore(waDirs)

        addLog("\n🎉 Done! Fixed: $fixed  Skipped: $skipped  Errors: $errors  Total: $total", LogType.SUCCESS)
        updateStats(Stats(total, fixed, skipped, errors, ""))
    }

    private fun setFileDates(file: File, dateMs: Long): Boolean {
        val current = file.lastModified()
        if (Math.abs(current - dateMs) < 2000) return false   // already within 2s

        // Set filesystem timestamps via File API
        file.setLastModified(dateMs)

        // Also update MediaStore if available (affects Gallery "date taken")
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, dateMs / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
                }
            }
            contentResolver.update(
                MediaStore.Files.getContentUri("external"),
                cv,
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(file.absolutePath)
            )
        } catch (_: Exception) {}

        return true
    }

    private fun notifyMediaStore(dirs: List<File>) {
        for (dir in dirs) {
            try {
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = Uri.fromFile(dir)
                sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    private fun addLog(msg: String, type: LogType) {
        CoroutineScope(Dispatchers.Main).launch { logs.add(LogEntry(msg, type)) }
    }

    private fun updateStats(s: Stats) {
        CoroutineScope(Dispatchers.Main).launch { stats.value = s }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    @Composable
    fun AppUI() {
        val s by stats
        val r by running
        val h by hasPerms
        val scrollState = rememberScrollState()

        // Auto-scroll log to bottom
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BgDeep)
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // Header
                    HeaderCard()

                    // Stats row
                    AnimatedVisibility(s.total > 0) { StatsRow(s) }

                    // Progress bar
                    AnimatedVisibility(r) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val inf = rememberInfiniteTransition(label = "prog")
                            val prog by inf.animateFloat(0f, 1f,
                                infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "p")
                            LinearProgressIndicator(
                                progress = { if (s.total > 0) s.fixed.toFloat() / s.total else prog },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(6.dp),
                                color = AccentGreen, trackColor = BgElevated
                            )
                            if (s.current.isNotEmpty())
                                Text(s.current, color = TextSecondary, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Log area
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCard)
                            .border(1.dp, BgElevated, RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Log output will appear here…", color = TextMuted,
                                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                }
                            }
                            logs.forEach { LogLine(it) }
                        }
                    }

                    // Buttons
                    if (!h) {
                        Button(
                            onClick = { requestPermissions() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Grant Storage Permission", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { if (r) stopFix() else startFix() },
                                modifier = Modifier.weight(1f).height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (r) AccentAmber else AccentGreen
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (r) "⏹  Stop" else "▶  Start Fixing",
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = BgDeep)
                            }
                            if (!r && logs.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { logs.clear(); stats.value = Stats() },
                                    modifier = Modifier.height(52.dp),
                                    border = BorderStroke(1.dp, BgElevated),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Clear", color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HeaderCard() {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF1A2332), Color(0xFF0D2137)))
                )
                .padding(16.dp)
        ) {
            Column {
                Text("📅 WhatsApp Date Fixer",
                    color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text("Restores original photo & video dates\nfrom WhatsApp filenames",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }

    @Composable
    fun StatsRow(s: Stats) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Total",   s.total.toString(),   TextPrimary,   Modifier.weight(1f))
            StatChip("Fixed",   s.fixed.toString(),   AccentGreen,   Modifier.weight(1f))
            StatChip("Skipped", s.skipped.toString(), AccentAmber,   Modifier.weight(1f))
            StatChip("Errors",  s.errors.toString(),  AccentRed,     Modifier.weight(1f))
        }
    }

    @Composable
    fun StatChip(label: String, value: String, color: Color, modifier: Modifier) {
        Column(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(BgCard)
                .border(1.dp, BgElevated, RoundedCornerShape(10.dp))
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = TextMuted, fontSize = 11.sp)
        }
    }

    @Composable
    fun LogLine(entry: LogEntry) {
        val color = when (entry.type) {
            LogType.SUCCESS -> AccentGreen
            LogType.ERROR   -> AccentRed
            LogType.WARN    -> AccentAmber
            LogType.INFO    -> TextSecondary
        }
        Text(entry.message, color = color, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
    }
}
