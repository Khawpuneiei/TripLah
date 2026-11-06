@file:UseSerializers(ZdtSerializer::class, LocalDateSerializer::class)

package triplah.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// ---------- cancellation risk signal ----------

enum class RiskLevel { LOW, MEDIUM, HIGH }

/** History for one flight number on one route, from the status provider or from our own records. */
@Serializable
data class FlightHistory(
    val flightNo: String,
    val days: Int,
    val operated: Int,
    val cancelled: Int,
    val delayedOver60: Int,
    val flightsPerDayOnRoute: Int,
    val lastFlightOfDay: Boolean,
    val carrierType: CarrierType,
)

@Serializable
data class RiskSignal(
    val flightNo: String,
    val level: RiskLevel,
    val score: Int,
    val reasons: List<String>,
    val basis: String,
    val disclaimer: String = DISCLAIMER,
) {
    companion object {
        const val DISCLAIMER = "Risk based on history. This is not a prediction that the flight will be cancelled. Seats sold per flight are not public."
    }
}

object Risk {
    fun signal(h: FlightHistory): RiskSignal {
        val reasons = mutableListOf<String>()
        var score = 0
        val total = h.operated + h.cancelled
        if (total < 10) {
            reasons += "only $total flights of history: the signal is weak"
        }
        val cancelRate = if (total == 0) 0.0 else h.cancelled.toDouble() / total
        val delayRate = if (h.operated == 0) 0.0 else h.delayedOver60.toDouble() / h.operated
        when {
            cancelRate >= 0.05 -> { score += 4; reasons += "cancelled ${pct(cancelRate)} of the last ${h.days} days" }
            cancelRate >= 0.02 -> { score += 2; reasons += "cancelled ${pct(cancelRate)} of the last ${h.days} days" }
            cancelRate > 0 -> { score += 1; reasons += "cancelled ${h.cancelled} time(s) in the last ${h.days} days" }
            else -> reasons += "no cancellations in the last ${h.days} days"
        }
        when {
            delayRate >= 0.25 -> { score += 2; reasons += "delayed over 1 hour on ${pct(delayRate)} of flights" }
            delayRate >= 0.10 -> { score += 1; reasons += "delayed over 1 hour on ${pct(delayRate)} of flights" }
        }
        if (h.flightsPerDayOnRoute <= 2) { score += 1; reasons += "only ${h.flightsPerDayOnRoute} flight(s) a day on this route, so few rebooking options" }
        if (h.lastFlightOfDay) { score += 1; reasons += "last flight of the day: a cancellation means staying overnight" }
        if (h.carrierType == CarrierType.LOW_COST) { score += 1; reasons += "low-cost airline: rebooking usually only on its own flights" }
        val level = when {
            score >= 5 -> RiskLevel.HIGH
            score >= 3 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return RiskSignal(h.flightNo, level, score, reasons, "history of ${h.days} days, $total flights")
    }

    private fun pct(x: Double) = "%.0f%%".format(x * 100)
}

// ---------- buy or wait ----------

enum class BuyAdvice { BUY_NOW, WAIT, NO_DATA }

@Serializable
data class BuyOrWait(val advice: BuyAdvice, val reason: String, val median: Double?, val label: String = "Estimate from our own price history. Not a forecast.")

object Pricing {
    /**
     * A simple rule, labelled as an estimate:
     * - book-by date reached, or under 14 days to go: buy (prices usually rise late).
     * - price at or below 90% of the route's median: buy.
     * - more than 30 days to go and above the median: wait.
     */
    fun buyOrWait(price: Double, history: List<Double>, today: LocalDate, departure: LocalDate, bookBy: LocalDate?): BuyOrWait {
        val days = ChronoUnit.DAYS.between(today, departure)
        val med = median(history)
        if (bookBy != null && !today.isBefore(bookBy)) return BuyOrWait(BuyAdvice.BUY_NOW, "the book-by date $bookBy is reached", med)
        if (days < 14) return BuyOrWait(BuyAdvice.BUY_NOW, "$days days to departure: late prices usually go up", med)
        if (med == null || history.size < 5) return BuyOrWait(BuyAdvice.NO_DATA, "only ${history.size} past prices for this route", med)
        return when {
            price <= med * 0.9 -> BuyOrWait(BuyAdvice.BUY_NOW, "price is ${pctBelow(price, med)} below the route median", med)
            days > 30 && price > med -> BuyOrWait(BuyAdvice.WAIT, "$days days to go and the price is above the route median", med)
            else -> BuyOrWait(BuyAdvice.BUY_NOW, "price is near the route median and waiting longer adds risk", med)
        }
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted(); val n = s.size
        return money(if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2)
    }

    private fun pctBelow(p: Double, m: Double) = "%.0f%%".format((1 - p / m) * 100)
}

// ---------- cost split ----------

@Serializable
data class SplitItem(val name: String, val amount: Double, val sharedBy: List<String>)

@Serializable
data class SplitResult(val perPerson: Map<String, Double>, val total: Double, val currency: String = "SGD")

object Split {
    /** Each item is split equally between the people who share it. Rounding cents go to the first person. */
    fun split(items: List<SplitItem>): SplitResult {
        val out = linkedMapOf<String, Double>()
        for (it in items) {
            if (it.sharedBy.isEmpty()) throw ValidationException("item '${it.name}' is shared by nobody")
            if (it.amount < 0) throw ValidationException("item '${it.name}' has a negative amount")
            val cents = Math.round(it.amount * 100)
            val each = cents / it.sharedBy.size
            val rest = cents - each * it.sharedBy.size
            it.sharedBy.forEachIndexed { i, p -> out[p] = (out[p] ?: 0.0) + (each + if (i == 0) rest else 0) / 100.0 }
        }
        return SplitResult(out.mapValues { money(it.value) }, money(items.sumOf { it.amount }))
    }
}

// ---------- flight recheck ----------

enum class FlightState { SCHEDULED, DELAYED, RETIMED, CANCELLED, DEPARTED, LANDED, UNKNOWN }

@Serializable
data class FlightStatus(
    val flightNo: String,
    val date: LocalDate,
    val state: FlightState,
    val scheduledDepart: ZonedDateTime,
    val scheduledArrive: ZonedDateTime,
    val estimatedDepart: ZonedDateTime? = null,
    val estimatedArrive: ZonedDateTime? = null,
    val source: String,
    val fetchedAt: String,
)

/** What we saved when the flight was watched: the plan's times and which leg it is. */
@Serializable
data class WatchedFlight(
    val flightNo: String,
    val date: LocalDate,
    val from: String,
    val to: String,
    val depart: ZonedDateTime,
    val arrive: ZonedDateTime,
    /** "outbound" leaves campus; "inbound" brings the traveller back to campus. */
    val leg: String,
    val travellers: List<String>,
)

@Serializable
data class RecheckResult(
    val flightNo: String,
    val changed: Boolean,
    val cancelled: Boolean,
    val moveMinutes: Long,
    val clashes: List<SkippedClass>,
    val message: String,
)

object Recheck {
    const val MOVE_THRESHOLD_MIN = 15L

    /**
     * Compare the current status with the watched plan. A move of 15 minutes or more, or a cancellation,
     * re-runs the timetable check: an inbound flight landing later can now clash with a class.
     */
    fun evaluate(w: WatchedFlight, s: FlightStatus, timetables: List<Timetable>, buffers: Buffers): RecheckResult {
        if (s.state == FlightState.CANCELLED) {
            return RecheckResult(w.flightNo, true, true, 0, emptyList(), "${w.flightNo} on ${w.date} is cancelled. Look for alternatives.")
        }
        val newDep = s.estimatedDepart ?: s.scheduledDepart
        val newArr = s.estimatedArrive ?: s.scheduledArrive
        val move = maxOf(Math.abs(Duration.between(w.depart, newDep).toMinutes()), Math.abs(Duration.between(w.arrive, newArr).toMinutes()))
        if (move < MOVE_THRESHOLD_MIN) return RecheckResult(w.flightNo, false, false, move, emptyList(), "${w.flightNo} is on time (moved $move min).")
        // Re-check the part of the trip this flight bounds.
        val clashes = if (w.leg == "inbound") {
            val oldBack = w.arrive.plusMinutes((buffers.immigrationOnArrivalMin + buffers.campusToAirportMin).toLong())
            val newBack = newArr.plusMinutes((buffers.immigrationOnArrivalMin + buffers.campusToAirportMin).toLong())
            if (newBack.isAfter(oldBack)) Windows.check(timetables, oldBack, newBack).mustAttend else emptyList()
        } else {
            val oldLeave = w.depart.minusMinutes((buffers.checkInBeforeDepartureMin + buffers.campusToAirportMin).toLong())
            val newLeave = newDep.minusMinutes((buffers.checkInBeforeDepartureMin + buffers.campusToAirportMin).toLong())
            if (newLeave.isBefore(oldLeave)) Windows.check(timetables, newLeave, oldLeave).mustAttend else emptyList()
        }
        val msg = buildString {
            append("${w.flightNo} on ${w.date} moved by $move min (now departs ${Planner.hhmm(newDep)}, lands ${Planner.hhmm(newArr)}).")
            if (clashes.isNotEmpty()) append(" It now clashes with ${clashes.joinToString { "${it.title} (${it.traveller}, ${Planner.hhmm(it.start)})" }}.")
            else append(" No must-attend class is affected.")
        }
        return RecheckResult(w.flightNo, true, false, move, clashes, msg)
    }

    /** Daily; every 6 hours in the last week; hourly in the last 24 hours. */
    fun nextCheck(now: ZonedDateTime, departure: ZonedDateTime): ZonedDateTime? {
        val left = Duration.between(now, departure)
        if (left.isNegative) return null
        val step = when {
            left <= Duration.ofHours(24) -> Duration.ofHours(1)
            left <= Duration.ofDays(7) -> Duration.ofHours(6)
            else -> Duration.ofDays(1)
        }
        return now.plus(step)
    }
}
