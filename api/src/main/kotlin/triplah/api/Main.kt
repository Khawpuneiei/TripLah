package triplah.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import triplah.core.Airport
import triplah.core.PrettyJson
import triplah.core.TripJson
import triplah.core.ValidationException
import triplah.core.Venue
import triplah.providers.HostNotAllowed
import triplah.providers.ProviderUnavailable
import triplah.providers.QuotaExceeded

private fun err(msg: String?) = buildJsonObject { put("error", msg ?: "error") }

fun Application.triplahModule(registry: ToolRegistry) {
    install(ContentNegotiation) { json(TripJson) }
    install(CORS) {
        allowHost("localhost:5173"); allowHost("127.0.0.1:5173")
        allowHeader(HttpHeaders.ContentType); allowMethod(HttpMethod.Post)
    }
    install(StatusPages) {
        exception<ValidationException> { call, e -> call.respond(HttpStatusCode.BadRequest, err(e.message)) }
        exception<kotlinx.serialization.SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, err("invalid JSON: ${e.message?.take(200)}")) }
        exception<QuotaExceeded> { call, e -> call.respond(HttpStatusCode.TooManyRequests, err(e.message)) }
        exception<HostNotAllowed> { call, e -> call.respond(HttpStatusCode.Forbidden, err(e.message)) }
        exception<ProviderUnavailable> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, err(e.message)) }
        exception<Throwable> { call, e -> call.application.environment.log.error("unhandled", e); call.respond(HttpStatusCode.InternalServerError, err("internal error")) }
    }
    val svc = registry.svc
    routing {
        get("/healthz") { call.respondText("ok") }
        get("/v1/airports") { call.respond(TripJson.encodeToJsonElement(ListSerializer(Airport.serializer()), svc.seed.airports)) }
        get("/v1/venues") { call.respond(TripJson.encodeToJsonElement(ListSerializer(Venue.serializer()), svc.seed.venues)) }
        get("/v1/tools") {
            call.respond(JsonArray(registry.tools.map { t -> buildJsonObject { put("name", t.name); put("description", t.description); put("inputSchema", t.inputSchema) } }))
        }
        post("/v1/tools/{name}") {
            val body = call.receiveText().ifBlank { "{}" }
            call.respond(registry.call(call.parameters["name"]!!, TripJson.parseToJsonElement(body)))
        }
        // Friendly aliases for the main endpoints.
        mapOf("/v1/plan" to "plan_trip", "/v1/flights/search" to "search_flights", "/v1/stays/search" to "search_stays",
            "/v1/timetables" to "import_timetable", "/v1/windows" to "find_free_windows", "/v1/split" to "split_budget",
            "/v1/watches/price" to "watch_price", "/v1/flights/check" to "check_flight", "/v1/flights/risk" to "flight_risk",
            "/v1/calendar" to "price_calendar").forEach { (path, tool) ->
            post(path) { call.respond(registry.call(tool, TripJson.parseToJsonElement(call.receiveText().ifBlank { "{}" }))) }
        }
        get("/openapi.json") { call.respondText(PrettyJson.encodeToString(JsonElement.serializer(), openApi(registry)), io.ktor.http.ContentType.Application.Json) }
    }
}

/** OpenAPI 3.1 spec generated from the same tool definitions the MCP server uses. */
fun openApi(registry: ToolRegistry): JsonObject = buildJsonObject {
    put("openapi", "3.1.0")
    put("info", buildJsonObject { put("title", "TripLah API"); put("version", "0.1.0"); put("description", "Trip planner for students. Plans only: never books or pays.") })
    put("servers", JsonArray(listOf(buildJsonObject { put("url", "http://127.0.0.1:8080") })))
    put("paths", JsonObject(registry.tools.associate { t ->
        "/v1/tools/${t.name}" to buildJsonObject {
            put("post", buildJsonObject {
                put("operationId", t.name); put("summary", t.description)
                put("requestBody", buildJsonObject { put("required", true); put("content", buildJsonObject { put("application/json", buildJsonObject { put("schema", t.inputSchema) }) }) })
                put("responses", buildJsonObject {
                    put("200", buildJsonObject { put("description", "Structured result") })
                    put("400", buildJsonObject { put("description", "Invalid input") })
                    put("429", buildJsonObject { put("description", "Provider quota guard tripped") })
                    put("503", buildJsonObject { put("description", "Provider unavailable") })
                })
            })
        }
    } + mapOf("/healthz" to buildJsonObject { put("get", buildJsonObject { put("responses", buildJsonObject { put("200", buildJsonObject { put("description", "ok") }) }) }) })))
}

fun main(args: Array<String>) {
    val registry = ToolRegistry(Wiring.fromEnv())
    if ("--print-openapi" in args) { println(PrettyJson.encodeToString(JsonElement.serializer(), openApi(registry))); return }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = System.getenv("HOST") ?: "127.0.0.1") { triplahModule(registry) }.start(wait = true)
}
