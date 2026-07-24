package com.mikeos.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.hive.HiveDirection
import com.mikeos.core.hive.HiveLogEntry
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Agent Inspector: a self-contained window into the live [MikeAgent] singleton.
 * Three sections — Messages (the hive log), Skills (the tools this agent has), and Soul
 * (persona + goals) — so the user can see exactly who this agent is, what it can do, and
 * what it has been saying to its siblings.
 *
 * Reads everything live from [MikeAgent.get]. If no agent is installed it shows a hint
 * instead of crashing. Drop it into any Compose host, or use [AgentInspectorActivity].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInspector(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val agent = remember { MikeAgent.get() }

    MikeAgentInspectorTheme {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = InspectorColors.Bg,
            topBar = {
                TopAppBar(
                    title = {
                        val name = agent?.soul?.appName ?: "MikeAgent"
                        Text("$name — Agent Inspector", color = InspectorColors.Text)
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            androidx.compose.material3.IconButton(onClick = onBack) {
                                Text(
                                    "←",
                                    color = InspectorColors.Text,
                                    fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = InspectorColors.Surface,
                        titleContentColor = InspectorColors.Text,
                    ),
                )
            },
        ) { padding ->
            if (agent == null) {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No agent installed.\nCall MikeAgent.install(...) first.",
                        color = InspectorColors.Dim,
                    )
                }
                return@Scaffold
            }

            var tab by remember { mutableIntStateOf(0) }
            val tabs = listOf("Messages", "Skills", "Soul", "Context")

            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = InspectorColors.Surface,
                    contentColor = InspectorColors.Accent,
                ) {
                    tabs.forEachIndexed { i, title ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = {
                                Text(
                                    title,
                                    color = if (tab == i) InspectorColors.Accent else InspectorColors.Dim,
                                )
                            },
                        )
                    }
                }
                when (tab) {
                    0 -> MessagesSection(agent.hiveLog.recent(200), agent.soul.appName)
                    1 -> SkillsSection(agent)
                    2 -> SoulSection(agent)
                    else -> ContextSection(agent)
                }
            }
        }
    }
}

@Composable
private fun MessagesSection(logFlow: Flow<List<HiveLogEntry>>, selfName: String) {
    val entries by logFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    if (entries.isEmpty()) {
        EmptyHint("No hive messages yet.\nSent and received messages appear here newest-first.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.id }) { entry -> MessageRow(entry, selfName) }
    }
}

@Composable
private fun MessageRow(entry: HiveLogEntry, selfName: String) {
    val sent = entry.direction == HiveDirection.SENT
    val dirColor = if (sent) InspectorColors.Accent else InspectorColors.Recv
    val label = if (sent) "Sent" else "Incoming"
    val fromName = if (sent) selfName else peerShort(entry.peer)
    val toName = if (sent) peerShort(entry.peer) else selfName
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = InspectorColors.Surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Thin left stripe: unread = orange, else direction colour.
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(if (!entry.read) InspectorColors.Unread else dirColor),
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Header: "Incoming: from → to"  ····  time
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("$label:", color = dirColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$fromName → $toName",
                        color = InspectorColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(formatTs(entry.ts), color = InspectorColors.Dim, fontSize = 11.sp)
                }
                // Type, small + dim (agent.question / place.arrived / capability.announce …).
                Text(entry.type, color = InspectorColors.Dim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.size(4.dp))
                // Message: 3-line snippet; tap the card to expand to the full JSON-pretty body.
                Text(
                    if (expanded) prettyBody(entry.body) else snippet(entry.body),
                    color = InspectorColors.Text,
                    fontSize = 13.sp,
                    fontFamily = if (expanded) FontFamily.Monospace else FontFamily.Default,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Trim the long hive agent name (`user/device/App`) down to just the app for display. */
private fun peerShort(peer: String): String =
    peer.substringAfterLast('/').ifBlank { peer }

/** Pretty-print a JSON body (agent messages are JSON); otherwise return it as-is. */
private fun prettyBody(body: String): String {
    val s = body.trim()
    return try {
        when {
            s.startsWith("{") -> org.json.JSONObject(s).toString(2)
            s.startsWith("[") -> org.json.JSONArray(s).toString(2)
            else -> body
        }
    } catch (e: Exception) {
        body
    }
}

@Composable
private fun SkillsSection(agent: MikeAgent) {
    val skills = remember(agent) { agent.skills.all() }
    if (skills.isEmpty()) {
        EmptyHint("This agent has no skills registered.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(skills, key = { it.name }) { skill ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = InspectorColors.Surface),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        skill.name,
                        color = InspectorColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(skill.description, color = InspectorColors.Text, fontSize = 13.sp)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        skill.paramsSchema,
                        color = InspectorColors.Dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SoulSection(agent: MikeAgent) {
    val soul = remember(agent) { agent.soul }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SoulCard("Identity") {
                LabeledLine("Agent", soul.agentName)
                LabeledLine("App", soul.appName)
            }
        }
        item {
            SoulCard("Persona") {
                Text(soul.persona, color = InspectorColors.Text, fontSize = 14.sp)
            }
        }
        item {
            SoulCard("Standing goals") {
                if (soul.goals.isEmpty()) {
                    Text("(none)", color = InspectorColors.Dim, fontSize = 14.sp)
                } else {
                    soul.goals.forEach {
                        Text("• $it", color = InspectorColors.Text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * Context tab — the user's PER-APP interest context (stored in mikeos-ai-cloud). What Mike
 * types here personalises THIS app's assistant (on top of the global context set in MikeAI).
 */
@Composable
private fun ContextSection(agent: MikeAgent) {
    val app = agent.soul.appName
    val key = agent.cred?.agentKey
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (key == null) "Not registered on the hive yet." else "Loading…") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        text = com.mikeos.core.net.AiContext.getAppContext(key, app)
        status = if (text.isBlank()) "No context for $app yet — add yours below." else "Loaded."
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("Your context for $app", color = InspectorColors.Text,
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.size(4.dp))
        Text("Tell this assistant what you care about / prefer for $app. It's added to your " +
            "global preferences (set in the MikeAI app) and personalises how $app reasons.",
            color = InspectorColors.Dim, fontSize = 12.sp)
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("e.g. vegetarian; love Thai & sushi; avoid seafood", color = InspectorColors.Dim) },
            enabled = key != null && !saving,
        )
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val k = key ?: return@Button
                    saving = true; status = "Saving…"
                    scope.launch {
                        val ok = com.mikeos.core.net.AiContext.putAppContext(k, app, text)
                        status = if (ok) "Saved ✓ — takes effect within a few minutes." else "Couldn't save — try again."
                        saving = false
                    }
                },
                enabled = key != null && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = InspectorColors.Accent),
            ) { Text("Save") }
            Spacer(Modifier.width(12.dp))
            Text(status, color = InspectorColors.Dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SoulCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InspectorColors.Surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title.uppercase(Locale.getDefault()),
                color = InspectorColors.Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            content()
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Row {
        Text("$label: ", color = InspectorColors.Dim, fontSize = 14.sp)
        Text(value, color = InspectorColors.Text, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = InspectorColors.Dim, fontSize = 14.sp)
    }
}

/**
 * A small, drop-in "Agent" icon button apps put in their top bar to open the inspector.
 * Renders a robot glyph; [onClick] typically calls `AgentInspectorActivity.start(context)`.
 */
@Composable
fun AgentIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = "Open Agent Inspector",
            // Explicit bright tint — the default LocalContentColor is often near-black
            // and vanishes on an app's dark header (why some apps showed no icon).
            tint = androidx.compose.ui.graphics.Color(0xFF5AA4FF),
        )
    }
}

/** A minimal dark theme so the inspector looks right regardless of the host app's theme. */
@Composable
internal fun MikeAgentInspectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = InspectorColors.Accent,
            background = InspectorColors.Bg,
            surface = InspectorColors.Surface,
            onBackground = InspectorColors.Text,
            onSurface = InspectorColors.Text,
        ),
        content = content,
    )
}

/** Clean dark palette, no external assets. */
private object InspectorColors {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val Text = Color(0xFFE6EDF3)
    val Dim = Color(0xFF8B949E)
    val Accent = Color(0xFF58A6FF)
    val Recv = Color(0xFF56D364)
    val Unread = Color(0xFFF0883E)
}

private val tsFormat = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
private fun formatTs(ts: Long): String = tsFormat.format(Date(ts))
private fun snippet(body: String): String =
    body.replace('\n', ' ').trim().let { if (it.length > 180) it.take(180) + "…" else it }
