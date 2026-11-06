package triplah.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import triplah.core.TripJson
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val ROOT = File(System.getProperty("triplah.root") ?: "..")
val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC)

/** A service on seed data with Traveller A (NTU .ics) and B (JSON) imported. */
fun seededService(clock: Clock = FIXED_CLOCK): TripService {
    val svc = Wiring.mock(clock = clock)
    svc.importTimetable(ImportTimetableIn("A", path = File(ROOT, "seed/timetables/traveller-a.ics").path))
    svc.importTimetable(ImportTimetableIn("B", path = File(ROOT, "seed/timetables/traveller-b.json").path))
    return svc
}

fun ToolRegistry.run(name: String, args: String): JsonObject = runBlocking { call(name, TripJson.parseToJsonElement(args)) as JsonObject }
