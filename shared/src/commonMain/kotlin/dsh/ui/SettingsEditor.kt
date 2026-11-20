package dsh.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dsh.transport.DshJson
import dsh.transport.PrettyJson
import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.num
import dsh.transport.obj
import dsh.transport.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** DSH settings schema graph (`{uid, refs:{id:{type, meta, dict|inner|list|value|sKey}}}`), Schemastery-style. */
class SchemaGraph(schema: JsonElement?) {
    private val refs: Map<String, JsonObject> = schema["refs"].obj?.mapValues { it.value as? JsonObject ?: JsonObject(emptyMap()) } ?: emptyMap()
    val root: JsonObject? = schema["uid"].long?.let { refs[it.toString()] }

    fun ref(id: JsonElement?): JsonObject? = id.long?.let { refs[it.toString()] }
    fun type(n: JsonObject?) = n["type"].str ?: "any"
    fun meta(n: JsonObject?) = n["meta"].obj ?: JsonObject(emptyMap())
    fun dict(n: JsonObject?): Map<String, JsonObject> = n["dict"].obj?.mapNotNull { (k, v) -> ref(v)?.let { k to it } }?.toMap() ?: emptyMap()
    fun inner(n: JsonObject?): JsonObject? = ref(n["inner"])
    fun list(n: JsonObject?): List<JsonObject> = n["list"].arr.orEmpty().mapNotNull { ref(it) }
    fun isSecret(n: JsonObject?) = meta(n)["role"].str == "secret"
    fun default(n: JsonObject?): JsonElement? = meta(n)["default"]

    /** Node at [path] below the root (object keys, dict keys, array indices). */
    fun nodeAt(path: List<String>): JsonObject? {
        var n = root
        for (seg in path) {
            n = when (type(n)) {
                "object" -> dict(n)[seg]
                "dict", "array" -> inner(n)
                else -> return null
            } ?: return null
        }
        return n
    }

    /** All union members are constants → enumerated choice. */
    fun constChoices(n: JsonObject?): List<JsonElement>? {
        if (type(n) != "union") return null
        val members = list(n)
        return if (members.isNotEmpty() && members.all { type(it) == "const" }) members.mapNotNull { it["value"] } else null
    }
}

/** Callbacks used by the editor; paths are absolute within the namespace. */
interface SettingsEditActions {
    fun set(path: List<String>, value: JsonElement)
    fun unset(path: List<String>)
}

fun humanize(key: String): String =
    key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

fun JsonElement?.at(path: List<String>): JsonElement? {
    var v = this
    for (p in path) v = when (v) { is JsonObject -> v[p]; is JsonArray -> p.toIntOrNull()?.let { v.getOrNull(it) }; else -> null }
    return v
}

/**
 * Renders an editable form for [node] at [path].
 * @param value effective value; @param user the user-layer value (non-null ⇒ overridden, can reset).
 */
@Composable
fun SchemaField(
    graph: SchemaGraph, node: JsonObject?, path: List<String>, label: String,
    value: JsonElement?, user: JsonElement?, secrets: Map<List<String>, Boolean>, actions: SettingsEditActions,
    depth: Int = 0,
) {
    val meta = graph.meta(node)
    val overridden = user != null
    val reset: (@Composable () -> Unit)? = if (overridden) ({
        IconButton({ actions.unset(path) }, Modifier.size(32.dp)) { Icon(Icons.Default.RestartAlt, "Reset to default", Modifier.size(18.dp)) }
    }) else null

    if (graph.isSecret(node)) {
        SecretField(label, secrets[path] == true, onSet = { actions.set(path, JsonPrimitive(it)) }, onClear = { actions.unset(path) })
        return
    }
    val choices = graph.constChoices(node)
    when {
        choices != null -> FieldRow(label, overridden, reset) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ open = true }) { Text(value.display().ifEmpty { "(default)" }, maxLines = 1) }
                DropdownMenu(open, { open = false }) {
                    choices.forEach { c -> DropdownMenuItem({ Text(c.display()) }, { open = false; actions.set(path, c) }) }
                }
            }
        }
        graph.type(node) == "boolean" -> FieldRow(label, overridden, reset) {
            Switch(value.bool ?: graph.default(node).bool ?: false, { actions.set(path, JsonPrimitive(it)) })
        }
        graph.type(node) == "number" -> FieldRow(label, overridden, reset) {
            val min = meta["min"].num; val max = meta["max"].num; val step = meta["step"].num
            CommitTextField(value.display(), numeric = true, hint = listOfNotNull(min?.let { "≥ ${it.fmt()}" }, max?.let { "≤ ${it.fmt()}" }).joinToString(" ")) { t ->
                val d = t.toDoubleOrNull() ?: return@CommitTextField "Not a number"
                if (min != null && d < min) return@CommitTextField "Must be ≥ ${min.fmt()}"
                if (max != null && d > max) return@CommitTextField "Must be ≤ ${max.fmt()}"
                actions.set(path, if (step == 1.0 || d == d.toLong().toDouble()) JsonPrimitive(d.toLong()) else JsonPrimitive(d)); null
            }
        }
        graph.type(node) == "string" -> FieldRow(label, overridden, reset) {
            CommitTextField(value.str ?: "", numeric = false, hint = meta["pattern"].str?.let { "pattern $it" } ?: "") { t ->
                if (t.isEmpty()) actions.unset(path) else actions.set(path, JsonPrimitive(t)); null
            }
        }
        graph.type(node) == "const" -> FieldRow(label, false, null) { Text(node["value"].display(), style = MonoStyle) }
        graph.type(node) == "object" -> {
            val fields = graph.dict(node)
            if (depth == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fields.forEach { (k, n) -> SchemaField(graph, n, path + k, humanize(k), value[k], user[k], secrets, actions, depth + 1) }
                }
            } else Collapsible(label, overridden, reset) {
                fields.forEach { (k, n) -> SchemaField(graph, n, path + k, humanize(k), value[k], user[k], secrets, actions, depth + 1) }
            }
        }
        graph.type(node) == "dict" -> Collapsible("$label (${(value as? JsonObject)?.size ?: 0})", overridden, reset, initiallyOpen = depth <= 1) {
            val inner = graph.inner(node)
            (value as? JsonObject)?.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { SchemaField(graph, inner, path + k, k, v, user[k], secrets, actions, depth + 1) }
                    IconButton({ actions.unset(path + k) }, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Remove $k", Modifier.size(18.dp)) }
                }
            }
            var adding by remember { mutableStateOf(false) }
            TextButton({ adding = true }) { Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text(" Add entry") }
            if (adding) TextPromptDialog("Add entry to $label", "", label = "Key", confirm = "Add", onDismiss = { adding = false }) { k ->
                adding = false
                actions.set(path + k, graph.default(inner) ?: if (graph.type(inner) == "object" || graph.type(inner) == "dict") JsonObject(emptyMap()) else JsonPrimitive(""))
            }
        }
        graph.type(node) == "array" && graph.type(graph.inner(node)).let { it == "string" || it == "number" } || (graph.type(node) == "array" && graph.constChoices(graph.inner(node)) != null) ->
            FieldRow(label, overridden, reset) {
                val items = (value as? JsonArray).orEmpty()
                val numeric = graph.type(graph.inner(node)) == "number"
                var adding by remember { mutableStateOf(false) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(max = 420.dp)) {
                    items.forEachIndexed { i, it ->
                        InputChip(false, { actions.set(path, JsonArray(items.filterIndexed { j, _ -> j != i })) }, { Text(it.display(), maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp)) })
                    }
                    IconButton({ adding = true }, Modifier.size(32.dp)) { Icon(Icons.Default.Add, "Add") }
                }
                if (adding) TextPromptDialog("Add to $label", "", confirm = "Add", onDismiss = { adding = false }) { t ->
                    adding = false
                    val el = if (numeric) t.toDoubleOrNull()?.let { JsonPrimitive(it) } else JsonPrimitive(t)
                    if (el != null) actions.set(path, JsonArray(items + el))
                }
            }
        else -> JsonField(label, value, overridden, reset) { actions.set(path, it) }
    }
}

private fun Double.fmt() = if (this == toLong().toDouble()) toLong().toString() else toString()

private fun JsonElement?.display(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> if (isString) content else content
    else -> toString()
}

@Composable
private fun FieldRow(label: String, overridden: Boolean, reset: (@Composable () -> Unit)?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (overridden) FontWeight.SemiBold else FontWeight.Normal)
            if (overridden) Text("customized", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        control()
        reset?.invoke()
    }
}

@Composable
private fun Collapsible(title: String, overridden: Boolean, reset: (@Composable () -> Unit)?, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            reset?.invoke()
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** Text field that commits on the ✓ button / Enter. [onCommit] returns an error message or null. */
@Composable
private fun CommitTextField(initial: String, numeric: Boolean, hint: String, onCommit: (String) -> String?) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = text != initial
    OutlinedTextField(
        text, { text = it; error = null }, singleLine = true, modifier = Modifier.widthIn(min = 120.dp, max = 320.dp),
        isError = error != null,
        supportingText = (error ?: hint.takeIf { it.isNotEmpty() })?.let { s -> { Text(s, style = MaterialTheme.typography.labelSmall) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (dirty) error = onCommit(text.trim()) }),
        trailingIcon = if (dirty) ({ IconButton({ error = onCommit(text.trim()) }) { Icon(Icons.Default.Check, "Save") } }) else null,
    )
}

@Composable
private fun SecretField(label: String, isSet: Boolean, onSet: (String) -> Unit, onClear: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Key, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label)
            Text(if (isSet) "Configured (hidden)" else "Not set", style = MaterialTheme.typography.labelSmall,
                color = if (isSet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (editing) {
            OutlinedTextField(text, { text = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.widthIn(max = 260.dp),
                trailingIcon = { IconButton({ if (text.isNotBlank()) onSet(text.trim()); editing = false; text = "" }) { Icon(Icons.Default.Check, "Save") } })
        } else {
            TextButton({ editing = true }) { Text(if (isSet) "Replace" else "Set") }
            if (isSet) TextButton(onClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Fallback for shapes without a dedicated control: edit the JSON directly. */
@Composable
private fun JsonField(label: String, value: JsonElement?, overridden: Boolean, reset: (@Composable () -> Unit)?, onSave: (JsonElement) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val pretty = value?.let { PrettyJson.encodeToString(JsonElement.serializer(), it) } ?: ""
    Collapsible("$label (JSON)", overridden, reset) {
        if (!editing) {
            CodeBlock(pretty.ifEmpty { "(not set)" }, maxLines = 30)
            TextButton({ editing = true }) { Text("Edit JSON") }
        } else {
            var text by remember { mutableStateOf(pretty) }
            var error by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(text, { text = it; error = null }, textStyle = MonoStyle, minLines = 4, modifier = Modifier.fillMaxWidth(), isError = error != null,
                supportingText = error?.let { e -> { Text(e) } })
            Row {
                TextButton({
                    runCatching { DshJson.parseToJsonElement(text) }.onSuccess { onSave(it); editing = false }.onFailure { error = "Invalid JSON: ${it.message}" }
                }) { Text("Save") }
                TextButton({ editing = false }) { Text("Cancel") }
            }
        }
    }
}
