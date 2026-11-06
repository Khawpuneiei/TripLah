package triplah.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import triplah.core.TripJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val registry = ToolRegistry(seededService())

    @Test
    fun `rest api - tools, plan, errors, openapi`() = testApplication {
        application { triplahModule(registry) }
        assertEquals("ok", client.get("/healthz").bodyAsText())
        val tools = TripJson.parseToJsonElement(client.get("/v1/tools").bodyAsText()) as JsonArray
        assertEquals(11, tools.size)

        val plan = client.post("/v1/plan") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"FAST","origin":"SIN","destination":"BKK","travellers":["A"],"event":{"name":"Award","start":"2026-11-20T14:00+07:00","end":"2026-11-20T17:00+07:00","venueId":"th-qsncc"}}""")
        }
        assertEquals(HttpStatusCode.OK, plan.status)
        assertEquals("OK", TripJson.parseToJsonElement(plan.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val bad = client.post("/v1/tools/search_flights") { contentType(ContentType.Application.Json); setBody("""{"from":"SIN","to":"ZZZ","date":"2026-11-20"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("ZZZ" in bad.bodyAsText())

        val notJson = client.post("/v1/tools/search_flights") { contentType(ContentType.Application.Json); setBody("{nope") }
        assertEquals(HttpStatusCode.BadRequest, notJson.status)

        val spec = TripJson.parseToJsonElement(client.get("/openapi.json").bodyAsText()) as JsonObject
        assertEquals("3.1.0", spec["openapi"]!!.jsonPrimitive.content)
        assertTrue("/v1/tools/plan_trip" in spec["paths"]!!.jsonObject)
    }

    @Test
    fun `committed openapi spec matches the code`() {
        val committed = java.io.File(ROOT, "api/openapi.json")
        val now = triplah.core.PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), openApi(registry))
        if (System.getenv("UPDATE_OPENAPI") == "1" || !committed.exists()) committed.writeText(now + "\n")
        assertEquals(now.trim(), committed.readText().trim(), "run with UPDATE_OPENAPI=1 to refresh api/openapi.json")
    }
}
