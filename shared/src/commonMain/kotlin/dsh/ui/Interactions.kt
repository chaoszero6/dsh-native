package dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.transport.Waterfall
import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Renders a pending server request (approval or question) for the current session. */
@Composable
fun InteractionCard(client: DshClient, w: Waterfall) {
    when (w.event) {
        "approval/request" -> ApprovalCard(client, w)
        "user-questions/request" -> QuestionsCard(client, w)
        else -> UnknownRequestCard(client, w)
    }
}

@Composable
private fun ApprovalCard(client: DshClient, w: Waterfall) {
    val scope = rememberCoroutineScope()
    var busy by remember(w.eventId) { mutableStateOf(false) }
    val r = w.request
    val reason = r["displayReason"]["en"].str ?: r["reason"].str
    fun answer(v: String) { busy = true; scope.launch { client.guard("Answer approval") { client.conn.answerWaterfall(w, JsonPrimitive(v)) }; busy = false } }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = LocalExtraColors.current.warn)
                Text("  Allow ${r["toolName"].str ?: "this tool"}?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ answer("allowed-once") }, enabled = !busy) { Text("Allow once") }
                OutlinedButton({ answer("rejected") }, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reject") }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private class QDraft { var selected = mutableStateMapOf<String, Boolean>(); var custom by mutableStateOf("") }

@Composable
private fun QuestionsCard(client: DshClient, w: Waterfall) {
    val scope = rememberCoroutineScope()
    val questions = w.request["questions"].arr.orEmpty()
    val drafts = remember(w.eventId) { questions.map { QDraft() } }
    var busy by remember(w.eventId) { mutableStateOf(false) }
    var error by remember(w.eventId) { mutableStateOf<String?>(null) }

    val planReview = questions.singleOrNull()?.let { q -> q["intent"]["kind"].str == "plan-review" } == true

    fun submit(answers: kotlinx.serialization.json.JsonArray) {
        busy = true; error = null
        scope.launch {
            client.guard("Answer") { client.conn.answerWaterfall(w, buildJsonObject { put("answers", answers) }) } ?: run { error = "Failed to send" }
            busy = false
        }
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QuestionAnswer, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (planReview) "  Review the plan" else "  The agent asks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (planReview) {
                val q = questions.single()
                q["question"].str?.let { Text(it, fontWeight = FontWeight.Medium) }
                q["detail"].str?.let { DshMarkdown(it) }
                val approve = q["intent"]["approve"].str ?: q["options"][0]["label"].str ?: "Approve"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        submit(buildJsonArray { add(buildJsonObject { put("id", q["id"].str ?: ""); put("selected", buildJsonArray { add(JsonPrimitive(approve)) }) }) })
                    }, enabled = !busy) { Text("Approve plan") }
                    OutlinedButton({ busy = true; scope.launch { client.guard("Decline") { client.conn.rejectWaterfall(w, "User wants to discuss the plan") }; busy = false } }, enabled = !busy) { Text("Keep discussing") }
                }
            } else {
                questions.forEachIndexed { i, q ->
                    val d = drafts[i]
                    val multi = q["multiSelect"].bool == true
                    q["header"].str?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    Text(q["question"].str ?: "", fontWeight = FontWeight.Medium)
                    q["detail"].str?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    for (o in q["options"].arr.orEmpty()) {
                        val label = o["label"].str ?: continue
                        val checked = d.selected[label] == true
                        Row(Modifier.fillMaxWidth().clickable {
                            if (multi) d.selected[label] = !checked else { d.selected.clear(); d.selected[label] = true; d.custom = "" }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            if (multi) Checkbox(checked, { d.selected[label] = it }) else RadioButton(checked, { d.selected.clear(); d.selected[label] = true; d.custom = "" })
                            Column {
                                Text(label)
                                o["description"].str?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    OutlinedTextField(d.custom, { d.custom = it; if (!multi && it.isNotBlank()) d.selected.clear() },
                        label = { Text(if (q["options"].arr.isNullOrEmpty()) "Your answer" else "Other (type your own)") },
                        modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        val answers = buildJsonArray {
                            questions.forEachIndexed { i, q ->
                                val d = drafts[i]
                                val custom = d.custom.trim()
                                val multi = q["multiSelect"].bool == true
                                add(buildJsonObject {
                                    put("id", q["id"].str ?: "q$i")
                                    put("selected", buildJsonArray { if (custom.isEmpty() || multi) d.selected.filterValues { it }.keys.forEach { add(JsonPrimitive(it)) } })
                                    if (custom.isNotEmpty()) put("custom", custom)
                                })
                            }
                        }
                        val incomplete = questions.indices.any { i -> drafts[i].custom.isBlank() && drafts[i].selected.none { it.value } }
                        if (incomplete) error = "Answer every question (or use Skip)" else submit(answers)
                    }, enabled = !busy) { Text("Submit") }
                    TextButton({
                        submit(buildJsonArray { questions.forEachIndexed { i, q -> add(buildJsonObject { put("id", q["id"].str ?: "q$i"); put("selected", buildJsonArray { }) }) } })
                    }, enabled = !busy) { Text("Skip") }
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun UnknownRequestCard(client: DshClient, w: Waterfall) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Server request: ${w.event}", fontWeight = FontWeight.Medium)
            CodeBlock(w.request.toString(), maxLines = 12)
            TextButton({ scope.launch { client.conn.passWaterfall(w) } }) { Text("Let another client handle it") }
        }
    }
}
