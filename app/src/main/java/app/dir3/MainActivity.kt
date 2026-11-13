package app.dir3

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root()
                }
            }
        }
    }
}

private val RED = Color(0xFFC62828)
private val ORANGE = Color(0xFFEF6C00)
private val GREEN = Color(0xFF2E7D32)

private fun fmt(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

@Composable
fun Root() {
    var tab by remember { mutableIntStateOf(0) }
    val items = listOf(
        "الرئيسية" to Icons.Filled.Home,
        "تنظيف" to Icons.Filled.Delete,
        "التطبيقات" to Icons.Filled.Warning,
        "الروابط" to Icons.Filled.Search,
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen()
                1 -> CleanScreen()
                2 -> AppsScreen()
                else -> LinksScreen()
            }
        }
    }
}

// ───────────── الرئيسية ─────────────

@Composable
fun Meter(title: String, frac: Float, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Monitor.read(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            s = withContext(Dispatchers.IO) { Monitor.read(ctx) }
            delay(2000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("لوحة المراقبة", style = MaterialTheme.typography.headlineSmall)
        Meter(
            "الذاكرة العشوائية (RAM)", s.ramUsedFrac,
            "${fmt(ctx, s.ramTotal - s.ramAvail)} من ${fmt(ctx, s.ramTotal)}",
        )
        Meter(
            "التخزين", s.storUsedFrac,
            "${fmt(ctx, s.storTotal - s.storAvail)} من ${fmt(ctx, s.storTotal)}",
        )
        Meter(
            "البطارية", s.battPct / 100f,
            "${s.battPct}% • ${"%.1f".format(s.battTempC)}°م • " + if (s.charging) "تشحن" else "لا تشحن",
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ملاحظات", fontWeight = FontWeight.Bold)
                Monitor.tips(s).forEach { Text("• $it") }
            }
        }
    }
}

// ───────────── التنظيف ─────────────

@Composable
fun FileRow(item: FileItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(File(item.path).name, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(item.path, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(fmt(ctx, item.size), color = ORANGE)
        }
    }
}

@Composable
fun CleanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var toDelete by remember { mutableStateOf<FileItem?>(null) }
    var msg by remember { mutableStateOf("") }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startScan() {
        if (!Storage.hasAccess(ctx)) {
            msg = "امنح التطبيق إذن الوصول للملفات ثم ارجع واضغط «فحص» مرة أخرى."
            if (Build.VERSION.SDK_INT >= 30) {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }
        msg = ""
        scope.launch {
            scanning = true
            result = withContext(Dispatchers.IO) { Storage.scan() }
            scanning = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("تنظيف التخزين", style = MaterialTheme.typography.headlineSmall)
        Text("يعرض الملفات الكبيرة (أكثر من 50 ميجا) والمؤقتة وملفات APK. لا يُحذف شيء قبل تأكيدك.")
        Button(onClick = { startScan() }, enabled = !scanning) { Text(if (scanning) "جاري الفحص..." else "فحص") }
        if (msg.isNotEmpty()) Text(msg, color = ORANGE)
        if (scanning) CircularProgressIndicator()

        val r = result
        if (r != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Text("ملفات كبيرة (${r.big.size})", fontWeight = FontWeight.Bold) }
                items(r.big, key = { "b" + it.path }) { f -> FileRow(f) { toDelete = f } }
                item { Text("مؤقتة وAPK (${r.junk.size})", fontWeight = FontWeight.Bold) }
                items(r.junk, key = { "j" + it.path }) { f -> FileRow(f) { toDelete = f } }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف الملف؟") },
            text = { Text("${File(f.path).name}\n${fmt(ctx, f.size)}\nلا يمكن التراجع عن الحذف.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = File(f.path).delete()
                    if (ok) {
                        result = result?.let { r ->
                            ScanResult(r.big.filter { it.path != f.path }, r.junk.filter { it.path != f.path })
                        }
                        msg = "تم الحذف."
                    } else {
                        msg = "تعذّر حذف الملف."
                    }
                    toDelete = null
                }) { Text("حذف", color = RED) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("إلغاء") } },
        )
    }
}

// ───────────── فحص التطبيقات ─────────────

@Composable
fun AppCard(a: AppRisk) {
    val ctx = LocalContext.current
    val level = Scanner.levelOf(a.score)
    val (txt, col) = when (level) {
        2 -> "خطورة عالية" to RED
        1 -> "خطورة متوسطة" to ORANGE
        else -> "منخفضة" to GREEN
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.padding(4.dp))
                Text(txt, color = col, fontWeight = FontWeight.Bold)
            }
            a.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("إعدادات التطبيق (حذف / صلاحيات / مسح الكاش)") }
        }
    }
}

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRisk>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Scanner.scanApps(ctx) } }

    val list = apps
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val high = list.count { Scanner.levelOf(it.score) == 2 }
            val mid = list.count { Scanner.levelOf(it.score) == 1 }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("فحص التطبيقات", style = MaterialTheme.typography.headlineSmall)
                Text("${list.size} تطبيق • $high عالية الخطورة • $mid متوسطة")
                Text(
                    "الدرجة تقدير مبني على الصلاحيات والمصدر، وليست حكماً بأن التطبيق خبيث. مثلاً الواتساب يطلب الكاميرا والميكروفون لسبب مشروع.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(list, key = { it.pkg }) { AppCard(it) }
    }
}

// ───────────── فحص الروابط ─────────────

@Composable
fun LinksScreen() {
    var input by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<LinkResult?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("فحص الروابط", style = MaterialTheme.typography.headlineSmall)
        Text("يحلل الرابط على جهازك فقط ولا يفتحه ولا يرسله لأي جهة.")
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("الصق الرابط هنا") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(onClick = { res = LinkCheck.check(input) }, enabled = input.isNotBlank()) { Text("افحص") }
        res?.let { r ->
            val col = when {
                r.score >= 5 -> RED
                r.score >= 2 -> ORANGE
                else -> GREEN
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.verdict, color = col, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    r.reasons.forEach { Text("• $it") }
                    if (r.score < 2) {
                        Text("عدم وجود مؤشرات لا يعني أن الرابط آمن.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
