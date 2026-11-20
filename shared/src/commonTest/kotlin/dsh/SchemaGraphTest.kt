package dsh

import dsh.ui.SchemaGraph
import dsh.ui.at
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaGraphTest {
    // Shape captured from a live `settings/describe` (ui-theme) plus a dict/secret extension.
    private val schema = Json.parseToJsonElement("""{"uid":10,"refs":{
      "1":{"type":"const","meta":{"required":true},"value":"light"},"2":{"type":"const","meta":{"required":true},"value":"dark"},
      "3":{"type":"union","meta":{"default":"system"},"list":[1,2]},"4":{"type":"number","meta":{"step":1,"min":12,"max":17,"default":14}},
      "5":{"type":"string","meta":{"role":"secret"}},"6":{"type":"object","meta":{},"dict":{"apiKey":5,"baseUrl":7}},
      "7":{"type":"string","meta":{}},"8":{"type":"dict","meta":{"default":{}},"inner":6,"sKey":7},
      "10":{"type":"object","meta":{"default":{}},"dict":{"preference":3,"fontSize":4,"providers":8}}}}""")

    @Test fun navigatesAndClassifies() {
        val g = SchemaGraph(schema)
        assertEquals("object", g.type(g.root))
        assertEquals(listOf(JsonPrimitive("light"), JsonPrimitive("dark")), g.constChoices(g.nodeAt(listOf("preference"))))
        assertEquals("number", g.type(g.nodeAt(listOf("fontSize"))))
        assertTrue(g.isSecret(g.nodeAt(listOf("providers", "anything", "apiKey"))))
        assertEquals("string", g.type(g.nodeAt(listOf("providers", "x", "baseUrl"))))
        val value = Json.parseToJsonElement("""{"providers":{"p1":{"baseUrl":"http://h"}}}""")
        assertEquals(JsonPrimitive("http://h"), value.at(listOf("providers", "p1", "baseUrl")))
    }
}
