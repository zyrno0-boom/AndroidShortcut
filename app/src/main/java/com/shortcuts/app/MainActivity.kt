package com.shortcuts.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ---------- Model ----------
data class Step(val type: String, val value: String)
data class Sc(val id: Long, val name: String, val color: Long, val steps: List<Step>)
data class Def(val type: String, val icon: String, val label: String, val hint: String?)

val INK = Color(0xFF1D1B22)
val COLORS = listOf(0xFFFF8A65, 0xFF4DD0B1, 0xFF6AB7FF, 0xFFB79CFF, 0xFFFFC247, 0xFFFF7AA8)
val DEFS = listOf(
    Def("torch", "🔦", "Toggle flashlight", null),
    Def("open", "🚀", "Open app", "App name, e.g. Maps"),
    Def("url", "🌐", "Open link", "https://example.com"),
    Def("sms", "💬", "Text someone", "Message to send"),
    Def("call", "📞", "Dial number", "Phone number"),
    Def("alarm", "⏰", "Set alarm", "Time as HH:MM"),
    Def("copy", "📋", "Copy text", "Text to copy"),
    Def("toast", "🔔", "Show message", "Message"),
    Def("wait", "⏳", "Wait", "Seconds"),
    Def("wifi", "📶", "Open Wi-Fi panel", null),
    Def("bt", "🎧", "Open Bluetooth settings", null)
)
fun def(t: String) = DEFS.firstOrNull { it.type == t } ?: DEFS[0]

fun seed() = listOf(
    Sc(1, "Leaving home", COLORS[0], listOf(Step("wifi", ""), Step("open", "Maps"))),
    Sc(2, "Bedtime", COLORS[3], listOf(Step("alarm", "06:45"), Step("toast", "Good night"))),
    Sc(3, "Text mom", COLORS[5], listOf(Step("sms", "Home safe!")))
)

fun load(c: Context): List<Sc> {
    val raw = c.getSharedPreferences("sc", 0).getString("d", null) ?: return seed()
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val s = o.getJSONArray("s")
            Sc(o.getLong("id"), o.getString("n"), o.getLong("c"),
                (0 until s.length()).map { j ->
                    val p = s.getJSONObject(j)
                    Step(p.getString("t"), p.getString("v"))
                })
        }
    } catch (e: Exception) { seed() }
}

fun save(c: Context, list: List<Sc>) {
    val a = JSONArray()
    list.forEach { sc ->
        val s = JSONArray()
        sc.steps.forEach { s.put(JSONObject().put("t", it.type).put("v", it.value)) }
        a.put(JSONObject().put("id", sc.id).put("n", sc.name).put("c", sc.color).put("s", s))
    }
    c.getSharedPreferences("sc", 0).edit().putString("d", a.toString()).apply()
}

// ---------- Actions ----------
var torchOn = false
fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

suspend fun perform(c: Context, s: Step) {
    fun go(i: Intent) { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    when (s.type) {
        "torch" -> {
            val m = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchOn = !torchOn
            m.setTorchMode(m.cameraIdList[0], torchOn)
        }
        "open" -> {
            val pm = c.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val hit = pm.queryIntentActivities(q, 0)
                .firstOrNull { it.loadLabel(pm).toString().contains(s.value.trim(), true) }
            if (hit != null) {
                go(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(hit.activityInfo.packageName, hit.activityInfo.name))
            } else toast(c, "No app named " + s.value)
        }
        "url" -> go(Intent(Intent.ACTION_VIEW, Uri.parse(if (s.value.startsWith("http")) s.value else "https://" + s.value)))
        "sms" -> go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", s.value))
        "call" -> go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(s.value))))
        "alarm" -> {
            val p = s.value.split(":")
            go(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, p[0].trim().toInt())
                .putExtra(AlarmClock.EXTRA_MINUTES, p.getOrElse(1) { "0" }.trim().toInt()))
        }
        "copy" -> (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("shortcut", s.value))
        "toast" -> toast(c, s.value)
        "wait" -> delay(((s.value.toDoubleOrNull() ?: 1.0) * 1000).toLong())
        "wifi" -> go(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        "bt" -> go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}

suspend fun runSc(c: Context, sc: Sc, onStep: (Int) -> Unit) {
    if (sc.steps.isEmpty()) { toast(c, "Add an action first"); return }
    sc.steps.forEachIndexed { i, st ->
        onStep(i)
        try { perform(c, st) } catch (e: Exception) { toast(c, "Couldn't run: " + def(st.type).label) }
        delay(600)
    }
    onStep(-1)
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA99BFF))
                else lightColorScheme(primary = Color(0xFF5B3DF5))
            ) { App() }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val list = remember { mutableStateListOf<Sc>().also { it.addAll(load(ctx)) } }
    var editing by remember { mutableStateOf<Long?>(null) }
    var running by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    fun persist() { save(ctx, list) }
    fun update(sc: Sc) {
        val i = list.indexOfFirst { it.id == sc.id }
        if (i >= 0) list[i] = sc
        persist()
    }
    val cur = list.firstOrNull { it.id == editing }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (cur == null) {
                Home(list,
                    onRun = { sc -> scope.launch { runSc(ctx, sc) { } } },
                    onEdit = { editing = it },
                    onNew = {
                        val sc = Sc(System.currentTimeMillis(), "New shortcut", COLORS[list.size % COLORS.size], emptyList())
                        list.add(sc); persist(); editing = sc.id
                    })
            } else {
                Editor(cur, running,
                    onChange = { update(it) },
                    onBack = { editing = null },
                    onDelete = { list.removeAll { it.id == cur.id }; persist(); editing = null },
                    onRun = { scope.launch { runSc(ctx, cur) { running = it } } })
            }
        }
    }
}

@Composable
fun Home(list: List<Sc>, onRun: (Sc) -> Unit, onEdit: (Long) -> Unit, onNew: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column {
            Text("Shortcuts", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp))
            Text("Tap to run, ✎ to edit", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
            LazyVerticalGrid(
                GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.id }) { sc -> Tile(sc, onRun, onEdit) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Text("＋ New shortcut", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun Tile(sc: Sc, onRun: (Sc) -> Unit, onEdit: (Long) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(28.dp))
            .background(Color(sc.color)).clickable { onRun(sc) }.padding(16.dp)
    ) {
        Text("✎", fontSize = 20.sp, color = INK,
            modifier = Modifier.align(Alignment.TopEnd).clickable { onEdit(sc.id) }.padding(8.dp))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(sc.steps.firstOrNull()?.let { def(it.type).icon } ?: "⚙️", fontSize = 26.sp)
            Text(sc.name, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = INK)
            Text(sc.steps.size.toString() + " actions", fontSize = 13.sp, color = INK)
        }
    }
}

@Composable
fun Mini(t: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.size(40.dp), enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text(t) }
}

@Composable
fun Editor(sc: Sc, running: Int, onChange: (Sc) -> Unit, onBack: () -> Unit, onDelete: () -> Unit, onRun: () -> Unit) {
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
            OutlinedTextField(sc.name, { onChange(sc.copy(name = it)) }, Modifier.weight(1f), singleLine = true)
            TextButton(onClick = onDelete) { Text("🗑") }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            COLORS.forEach { c ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                        .border(3.dp, if (c == sc.color) MaterialTheme.colorScheme.onBackground else Color.Transparent, CircleShape)
                        .clickable { onChange(sc.copy(color = c)) }
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(sc.steps) { i, st ->
                StepCard(i, sc.steps.size, st, i == running,
                    onChange = { n -> onChange(sc.copy(steps = sc.steps.toMutableList().also { it[i] = n })) },
                    onMove = { dir ->
                        val l = sc.steps.toMutableList()
                        val t = l[i]; l[i] = l[i + dir]; l[i + dir] = t
                        onChange(sc.copy(steps = l))
                    },
                    onDel = { onChange(sc.copy(steps = sc.steps.filterIndexed { k, _ -> k != i })) })
            }
            if (sc.steps.isEmpty()) item {
                Text("Empty. Tap “Add action” to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ picker = true }, Modifier.weight(1f).height(52.dp)) { Text("Add action") }
            Button(onRun, Modifier.weight(1f).height(52.dp)) { Text("▶ Run") }
        }
    }
    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            confirmButton = {},
            title = { Text("Add action") },
            text = {
                LazyColumn {
                    items(DEFS) { d ->
                        TextButton(
                            onClick = {
                                onChange(sc.copy(steps = sc.steps + Step(d.type, if (d.type == "wait") "2" else "")))
                                picker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(d.icon + "  " + d.label, Modifier.fillMaxWidth()) }
                    }
                }
            }
        )
    }
}

@Composable
fun StepCard(i: Int, n: Int, st: Step, active: Boolean, onChange: (Step) -> Unit, onMove: (Int) -> Unit, onDel: () -> Unit) {
    val d = def(st.type)
    val hint = d.hint
    Card(
        Modifier.fillMaxWidth().border(2.dp, if (active) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.icon, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label, fontWeight = FontWeight.SemiBold)
                if (hint != null) {
                    OutlinedTextField(st.value, { onChange(st.copy(value = it)) }, Modifier.fillMaxWidth(),
                        placeholder = { Text(hint) }, singleLine = true)
                }
            }
            Column {
                Mini("▲", i > 0) { onMove(-1) }
                Mini("▼", i < n - 1) { onMove(1) }
            }
            Mini("✕") { onDel() }
        }
    }
}
