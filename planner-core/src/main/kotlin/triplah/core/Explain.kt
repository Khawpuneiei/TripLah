package triplah.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.format.DateTimeFormatter

/** Plain-text summary built only from fields in the result, so every number in it can be traced. */
object Explain {
    private val dm = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
    fun fmt(x: Double) = "SGD %.2f".format(x)

    fun summary(r: PlanResult): String = buildString {
        when (r.status) {
            PlanStatus.NO_PLAN -> {
                append("No plan fits. ")
                r.rejected.entries.sortedByDescending { it.value }.take(3).joinTo(this, "; ", "Main reasons: ", ".") { "${it.key} (${it.value})" }
            }
            PlanStatus.NO_PLAN_IN_BUDGET -> {
                append("No plan fits the budget. ")
                r.cheapestOverBudget?.let { append("The cheapest plan that fits the timetable costs ${fmt(it.costs.total)}.") }
            }
            PlanStatus.OK -> r.plans.forEach { p ->
                append("${p.label.replaceFirstChar { it.uppercase() }}: ")
                p.flights.joinTo(this, "; ") { f ->
                    "${f.flightNos} ${f.from} ${f.depart.format(dm)} → ${f.to} ${Planner.hhmm(f.arrive)}" + (if (f.stops > 0) " (${f.stops} stop)" else "")
                }
                p.stay?.let { append("; stay ${it.name}, ${it.roomCount} × ${it.roomName}, ${it.nights} night(s)") }
                append(". Total ${fmt(p.costs.total)} (${fmt(p.costs.perPerson)} each).")
                if (p.skippedClasses.isNotEmpty()) append(" Misses can-skip: ${p.skippedClasses.joinToString { it.title }}.")
                append("\n")
            }
        }
    }.trim()
}

/**
 * Checks that every price, time and flight number in an answer appears in a tool result.
 * Prices: numbers after a currency code. Times: HH:MM. Flight numbers: two-character airline code + 1-4 digits.
 */
object Grounding {
    private val price = Regex("""(?:SGD|THB|USD|S\$|\$)\s?(\d{1,6}(?:,\d{3})*(?:\.\d{1,2})?)""")
    private val time = Regex("""(?<!\d)([01]\d|2[0-3]):([0-5]\d)(?!\d)""")
    private val flight = Regex("""\b([A-Z][A-Z0-9]|[A-Z0-9][A-Z])\s?(\d{1,4})\b""")

    data class Report(val ungroundedPrices: List<String>, val ungroundedTimes: List<String>, val ungroundedFlights: List<String>) {
        val ok get() = ungroundedPrices.isEmpty() && ungroundedTimes.isEmpty() && ungroundedFlights.isEmpty()
    }

    fun check(answer: String, toolResults: List<JsonElement>): Report {
        val numbers = mutableSetOf<Double>(); val strings = mutableListOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.values.forEach(::walk)
                is JsonArray -> e.forEach(::walk)
                is JsonPrimitive -> { if (e.isString) strings += e.content else e.doubleOrNull?.let { numbers += it } }
            }
        }
        toolResults.forEach(::walk)
        strings.forEach { s -> s.toDoubleOrNull()?.let { numbers += it } }
        val text = strings.joinToString("\n")
        val times = time.findAll(text).map { it.value }.toSet()
        val flights = strings.flatMap { s -> flight.findAll(s).map { it.groupValues[1] + it.groupValues[2] }.toList() }.toSet()

        val badPrices = price.findAll(answer).map { it.groupValues[1] }.filter { v ->
            val d = v.replace(",", "").toDouble(); numbers.none { kotlin.math.abs(it - d) < 0.005 }
        }.toList()
        val badTimes = time.findAll(answer).map { it.value }.filter { it !in times }.toList()
        val badFlights = flight.findAll(answer).map { it.groupValues[1] + it.groupValues[2] }
            .filter { it.take(2).any(Char::isLetter) && it !in flights }.toList()
        return Report(badPrices, badTimes, badFlights)
    }
}
