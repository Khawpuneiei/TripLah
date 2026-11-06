package triplah.evals

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import triplah.api.ImportTimetableIn
import triplah.api.ToolRegistry
import triplah.api.Wiring
import triplah.core.*
import triplah.providers.Store
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class Expect(
    val status: String,
    val plans: Int? = null,
    val noStay: Boolean = false,
    val comparison: Boolean = false,
    val noteContains: String? = null,
)

@Serializable
data class EvalCase(
    val id: String,
    val painPoint: Int,
    val prompt: String,
    val tool: String = "plan_trip",
    val request: JsonObject,
    val expect: Expect,
)

@Serializable
data class EvalFile(val clock: String, val timetables: Map<String, String>, val cases: List<EvalCase>)

data class CaseResult(val case: EvalCase, val mode: String, val millis: Double, val failures: List<String>) {
    val passed get() = failures.isEmpty()
}

/**
 * A plan passes if it has zero must-attend conflicts, stays within budget, meets its mode's rules,
 * and every price, time and flight number in the answer appears in a tool result.
 * Each check is re-done here from the plan's fields, independent of the planner's own filtering.
 */
class EvalRunner(private val root: File) {
    val file: EvalFile = TripJson.decodeFromString(EvalFile.serializer(), File(root, "evals/cases.json").readText())

    fun run(): List<CaseResult> {
        val clock = Clock.fixed(Instant.parse(file.clock), ZoneOffset.UTC)
        val svc = Wiring.mock(Store.memory(), clock)
        file.timetables.forEach { (id, path) -> svc.importTimetable(ImportTimetableIn(id, path = File(root, path).path)) }
        val reg = ToolRegistry(svc)
        val tts = svc.store.timetables()
        // Warm up the JVM once so the first case's timing is not class loading.
        runCatching { runBlocking { reg.call(file.cases.first().tool, file.cases.first().request) } }
        return file.cases.map { c ->
            svc.hub.clearCaches()
            val t = System.nanoTime()
            val out = runCatching { runBlocking { reg.call(c.tool, c.request) } }
            val ms = (System.nanoTime() - t) / 1e6
            val mode = (c.request["mode"]?.let { TripJson.decodeFromJsonElement(kotlinx.serialization.json.JsonPrimitive.serializer(), it).content }) ?: "CALENDAR"
            val failures = out.fold(
                onSuccess = { json -> if (c.tool == "plan_trip") checkPlan(c, TripJson.decodeFromJsonElement(PlanResult.serializer(), json), json as JsonObject, tts) else checkCalendar(c, json as JsonObject) },
                onFailure = { listOf("tool error: ${it.message}") },
            )
            CaseResult(c, mode, ms, failures)
        }
    }

    private fun checkCalendar(c: EvalCase, json: JsonObject): List<String> {
        val n = json["options"]!!.jsonArray.size
        return if ((n > 0) == (c.expect.status == "OK")) emptyList() else listOf("expected ${c.expect.status}, got $n options")
    }

    fun checkPlan(c: EvalCase, r: PlanResult, json: JsonObject, tts: Map<String, Timetable>): List<String> {
        val f = mutableListOf<String>()
        val req = TripJson.decodeFromJsonElement(TripRequest.serializer(), c.request)
        if (r.status.name != c.expect.status) f += "status ${r.status}, expected ${c.expect.status}"
        c.expect.plans?.let { if (r.plans.size != it) f += "${r.plans.size} plans, expected $it" }
        if (c.expect.noStay && r.plans.any { it.stay != null }) f += "a stay was planned although the organiser covers it"
        if (c.expect.comparison && r.stayComparison.size != 2) f += "no pods-vs-twin comparison"
        c.expect.noteContains?.let { s -> if (r.notes.none { s in it }) f += "no note mentioning '$s'" }

        for (p in r.plans) {
            // 1. Zero must-attend conflicts.
            for ((who, away) in p.away) {
                val tt = tts[who.removePrefix("Traveller ")] ?: continue
                val back = away.backOnCampus ?: p.flights.first { it.from == req.destination || it.leg.startsWith("leg 2") }.depart
                val clash = Windows.check(listOf(tt), away.leaveCampus, back).mustAttend
                if (clash.isNotEmpty()) f += "plan ${p.rank}: $who misses must-attend ${clash.joinToString { it.title }}"
            }
            // 2. Within budget.
            req.budget?.let { if (p.costs.total > it + 0.001) f += "plan ${p.rank}: total ${p.costs.total} over budget $it" }
            if (kotlin.math.abs(p.costs.flights + p.costs.stay - p.costs.total) > 0.02) f += "plan ${p.rank}: costs do not add up"
            // 3. Mode rules.
            val main = p.flights.filterNot { it.leg.startsWith("leg 3") }
            when (r.mode) {
                Mode.FAMILY -> {
                    main.forEach { fl ->
                        if (fl.stops > 0) f += "plan ${p.rank}: FAMILY flight ${fl.flightNos} has a stop"
                        if (fl.redEye) f += "plan ${p.rank}: FAMILY flight ${fl.flightNos} is a red-eye"
                        if (!fl.checkedBag) f += "plan ${p.rank}: FAMILY flight ${fl.flightNos} has no checked bag"
                    }
                    p.stay?.let { s ->
                        if (s.roomKind in setOf(RoomKind.POD, RoomKind.DORM_BED)) f += "plan ${p.rank}: FAMILY stay is not private"
                        if (s.roomKind !in setOf(RoomKind.FAMILY_ROOM, RoomKind.SUITE) && s.sizeM2 != null && s.sizeM2!! < 25) f += "plan ${p.rank}: room under 25 m²"
                        if (s.hasLift == false) f += "plan ${p.rank}: no lift"
                        if ((s.mrtWalkMin ?: 0) > 10) f += "plan ${p.rank}: MRT walk too long"
                    }
                    if (req.painPoint == PainPoint.FAMILY_VISIT) {
                        val l1 = p.flights.first(); val l2 = p.flights[1]
                        if (l1.pax != req.familySize) f += "plan ${p.rank}: leg 1 seats ${l1.pax}"
                        if (l2.pax != req.familySize!! + (if (req.hostTraveller != null) 1 else 0)) f += "plan ${p.rank}: leg 2 is not one booking for everyone"
                    }
                }
                Mode.LOW_COST_CONVENIENT -> p.stay?.let { s ->
                    if ((s.minutesToVenue ?: 99) > 15) f += "plan ${p.rank}: stay ${s.minutesToVenue} min from venue"
                    if (s.roomKind in setOf(RoomKind.POD, RoomKind.DORM_BED) && s.type !in setOf(StayType.CAPSULE, StayType.HOSTEL)) f += "plan ${p.rank}: shared bed outside a capsule/hostel"
                }
                Mode.FAST -> {
                    if (r.plans.size > 2) f += "FAST returned ${r.plans.size} plans"
                    if (r.plans.any { pl -> pl.flights.all { it.stops == 0 } } && main.any { it.stops > 0 }) f += "plan ${p.rank}: FAST used a stop although a direct plan exists"
                }
                Mode.LOW_COST -> main.forEach { if (it.stops > 1) f += "plan ${p.rank}: more than one stop" }
            }
        }
        // 4. Grounding: every price, time and flight number in the answer appears in the tool result.
        val g = Grounding.check(r.summary, listOf(TripJson.encodeToJsonElement(PlanResult.serializer(), r.copy(summary = ""))))
        if (!g.ok) f += "ungrounded answer: $g"
        return f
    }

    fun report(results: List<CaseResult>): String = buildString {
        val pass = results.count { it.passed }
        appendLine("# TripLah eval report")
        appendLine()
        appendLine("Seed data, mock providers, clock fixed at ${file.clock}. Generated by `./gradlew :evals:run`.")
        appendLine()
        appendLine("**$pass / ${results.size} passed.**")
        appendLine()
        appendLine("| Pain point | Passed |")
        appendLine("|---|---|")
        results.groupBy { it.case.painPoint }.toSortedMap().forEach { (pp, rs) -> appendLine("| $pp | ${rs.count { it.passed }} / ${rs.size} |") }
        appendLine()
        appendLine("## Speed (seed data, caches cleared per case)")
        appendLine()
        appendLine("| Mode | Cases | p50 ms | p95 ms | Target |")
        appendLine("|---|---|---|---|---|")
        results.groupBy { it.mode }.toSortedMap().forEach { (m, rs) ->
            val ms = rs.map { it.millis }.sorted()
            appendLine("| $m | ${ms.size} | ${"%.0f".format(pct(ms, 0.5))} | ${"%.0f".format(pct(ms, 0.95))} | < 1000 |")
        }
        appendLine()
        appendLine("## Cases")
        appendLine()
        appendLine("| Id | Expected | Result | ms | Prompt |")
        appendLine("|---|---|---|---|---|")
        results.forEach { r -> appendLine("| ${r.case.id} | ${r.case.expect.status} | ${if (r.passed) "pass" else "FAIL: " + r.failures.joinToString("; ").replace("|", "/")} | ${"%.0f".format(r.millis)} | ${r.case.prompt} |") }
    }

    companion object {
        fun pct(sorted: List<Double>, p: Double): Double = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }
}

fun main(args: Array<String>) {
    val root = File(System.getProperty("triplah.root") ?: args.firstOrNull() ?: ".").absoluteFile
    val runner = EvalRunner(root)
    val results = runner.run()
    val report = runner.report(results)
    File(root, "evals/report.md").writeText(report)
    println(report)
    if (results.any { !it.passed }) kotlin.system.exitProcess(1)
}
