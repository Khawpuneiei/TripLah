package triplah.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import triplah.api.ImportTimetableIn
import triplah.api.ToolRegistry
import triplah.api.Wiring
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    private val root = File(System.getProperty("triplah.root") ?: "..")
    private val svc = Wiring.mock(clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-10T01:00:00Z"), java.time.ZoneOffset.UTC)).also {
        it.importTimetable(ImportTimetableIn("A", path = File(root, "seed/timetables/traveller-a.ics").path))
    }
    private val server = McpServer(InProcessBackend(ToolRegistry(svc)))

    @Test
    fun `initialize, list tools, call a tool`() {
        val init = server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")!!
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))

        val list = server.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
        val names = list["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("import_timetable", "find_free_windows", "search_flights", "search_stays", "plan_trip", "split_budget", "watch_price", "check_flight", "flight_risk", "ask_travel_rules")))

        val call = server.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"plan_trip","arguments":{"mode":"FAST","origin":"SIN","destination":"BKK","travellers":["A"],"event":{"name":"Award","start":"2026-11-20T14:00+07:00","end":"2026-11-20T17:00+07:00","venueId":"th-qsncc"}}}}""")!!
        val res = call["result"]!!.jsonObject
        assertEquals("false", res["isError"]!!.jsonPrimitive.content)
        assertEquals("OK", res["structuredContent"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool errors are results, protocol errors are errors`() {
        val bad = server.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_flights","arguments":{"from":"SIN","to":"QQQ","date":"2026-11-20"}}}""")!!
        assertEquals("true", bad["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)
        assertEquals(-32601, server.handle("""{"jsonrpc":"2.0","id":5,"method":"nope"}""")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(-32700, server.handle("not json")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `serve reads newline-delimited messages and writes one response per request`() {
        val out = ByteArrayOutputStream()
        val s = McpServer(InProcessBackend(ToolRegistry(svc)), PrintStream(out, true, "UTF-8"))
        s.serve(BufferedReader(StringReader("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","id":2,"method":"ping"}
        """.trimIndent())))
        val lines = out.toString(Charsets.UTF_8).trim().lines()
        assertEquals(2, lines.size)
        lines.forEach { assertTrue(it.startsWith("{\"jsonrpc\":\"2.0\"")) }
    }
}
