package triplah.core

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class StayKey(val checkIn: LocalDate, val checkOut: LocalDate) {
    val nights get() = ChronoUnit.DAYS.between(checkIn, checkOut).toInt()
}

/** Everything the planner needs, already fetched. The planner itself does no I/O. */
data class PlannerInput(
    val request: TripRequest,
    val timetables: Map<String, Timetable>,
    /** EVENT_TRIP: outbound/inbound. FAMILY_VISIT: leg1 (family in), leg2 (everyone out), leg3 (host back). */
    val flights: Map<String, List<FlightOffer>>,
    val stays: Map<StayKey, List<StayOffer>> = emptyMap(),
    /** Minutes from each stay to the venue, by stay id. */
    val minutesToVenue: Map<String, Int> = emptyMap(),
    /** Minutes between the destination airport and the venue. */
    val airportToVenueMin: Int = 40,
    val now: ZonedDateTime,
)

private class Counter { val m = linkedMapOf<String, Int>(); fun add(r: String) { m[r] = (m[r] ?: 0) + 1 } }

private data class Candidate(
    val flights: List<Pair<String, FlightOffer>>,
    val stay: StayChoice?,
    val away: Map<String, AwayWindow>,
    val conflict: Conflict,
    val warnings: List<String>,
    val total: Double,
    val pax: Int,
) {
    val allDirect get() = flights.all { it.second.stops == 0 }
}

object Planner {
    const val FAST_THRESHOLD_HOURS = 72L

    fun plan(input: PlannerInput): PlanResult = when (input.request.painPoint) {
        PainPoint.EVENT_TRIP -> planEventTrip(input)
        PainPoint.FAMILY_VISIT -> planFamilyVisit(input)
    }

    // ---------- pain points 1 and 2 ----------

    private fun planEventTrip(input: PlannerInput): PlanResult {
        val r = input.request
        val ev = r.event ?: throw ValidationException("EVENT_TRIP needs an event")
        val mode = r.mode
        val pax = r.passengers ?: r.travellers.size.coerceAtLeast(1)
        val rejected = Counter()
        val notes = mutableListOf<String>()
        val tts = r.travellers.mapNotNull { id -> input.timetables[id] ?: null.also { notes += "No timetable for Traveller $id: their classes were not checked." } }
        val bag = ModeRules.checkedBag(mode)
        val b = r.buffers

        fun ok(leg: String, f: FlightOffer): Boolean {
            ModeRules.rejectFlight(mode, f, pax)?.let { rejected.add(it); return false }
            val fits = if (leg == "outbound")
                !f.arrive.plusMinutes((b.immigrationOnArrivalMin + input.airportToVenueMin).toLong()).isAfter(ev.start)
            else !f.depart.minusMinutes((b.checkInBeforeDepartureMin + input.airportToVenueMin).toLong()).isBefore(ev.end)
            if (!fits) rejected.add(if (leg == "outbound") "arrives too late for the event" else "leaves before the event ends")
            return fits
        }
        val outs = input.flights["outbound"].orEmpty().filter { ok("outbound", it) }
        val ins = input.flights["inbound"].orEmpty().filter { ok("inbound", it) }

        // Best stay per check-in/check-out pair, computed once.
        val stayCache = HashMap<StayKey, Pair<List<StayChoice>, List<StayComparison>>>()
        val staysPerPair = if (mode == Mode.FAMILY || mode == Mode.LOW_COST_CONVENIENT) 2 else 1
        fun bestStays(key: StayKey): Pair<List<StayChoice>, List<StayComparison>> = stayCache.getOrPut(key) {
            val all = input.stays[key].orEmpty().flatMap { s ->
                val (choices, reason) = ModeRules.stayOptions(mode, s, pax, key.nights, input.minutesToVenue[s.id])
                if (reason != null) rejected.add("stay: $reason")
                choices
            }.sortedBy { ModeRules.stayScore(mode, it) }
            val comparison = if (mode == Mode.LOW_COST_CONVENIENT && pax >= 2) listOfNotNull(
                all.firstOrNull { !it.roomKind.let { k -> k == RoomKind.PRIVATE_TWIN || k == RoomKind.PRIVATE_DOUBLE } }?.let {
                    StayComparison("$pax × ${it.roomKind.name.lowercase().replace('_', ' ')}", it.name, it.total, money(it.total / pax), it.minutesToVenue)
                },
                all.firstOrNull { it.roomKind == RoomKind.PRIVATE_TWIN || it.roomKind == RoomKind.PRIVATE_DOUBLE }?.let {
                    StayComparison("1 × ${it.roomKind.name.lowercase().replace('_', ' ')} room", it.name, it.total, money(it.total / pax), it.minutesToVenue)
                },
            ) else emptyList()
            all.distinctBy { it.stayId }.take(staysPerPair) to comparison
        }

        val cands = mutableListOf<Candidate>()
        var comparison = emptyList<StayComparison>()
        for (o in outs) for (i in ins) {
            if (!i.depart.isAfter(o.arrive)) continue
            val leave = o.depart.minusMinutes((b.checkInBeforeDepartureMin + b.campusToAirportMin).toLong())
            val back = i.arrive.plusMinutes((b.immigrationOnArrivalMin + b.campusToAirportMin).toLong())
            val conflict = Windows.check(tts, leave, back)
            if (!conflict.ok) { rejected.add("breaks a must-attend class"); continue }
            val key = StayKey(o.arrive.toLocalDate(), i.depart.toLocalDate())
            var stayOptions: List<StayChoice?> = listOf(null)
            if (key.nights > 0 && !r.organiserCoversStay) {
                val (s, cmp) = bestStays(key)
                if (s.isEmpty()) { rejected.add("no stay that meets the $mode rules"); continue }
                stayOptions = s; if (comparison.isEmpty()) comparison = cmp
            }
            for (stay in stayOptions) {
            val warnings = buildList {
                listOf("outbound" to o, "inbound" to i).forEach { (leg, f) ->
                    if (ModeRules.isLateNight(f.arrive)) add("$leg ${f.flightNos} lands at ${hhmm(f.arrive)} local time (late night)")
                    if (!f.singleTicket) add("$leg ${f.flightNos} is a self-transfer on separate tickets: a missed connection is not protected")
                }
                stay?.unknown?.forEach { add("stay ${stay.name}: $it unknown") }
            }
            val total = money(o.price(bag) + i.price(bag) + (stay?.total ?: 0.0))
            val away = tts.associate { it.label to AwayWindow(leave, back) }
            cands += Candidate(listOf("outbound" to o, "inbound" to i), stay, away, conflict, warnings, total, pax)
            }
        }
        if (mode == Mode.FAST && cands.any { it.allDirect }) {
            val before = cands.size; cands.retainAll { it.allDirect }
            repeat(before - cands.size) { rejected.add("FAST: direct flights first") }
        }

        val hoursToStart = Duration.between(input.now, ev.start).toHours()
        if (mode == Mode.FAST && hoursToStart > FAST_THRESHOLD_HOURS) notes += "The event is $hoursToStart hours away. LOW_COST may find a cheaper plan."
        if (mode != Mode.FAST && hoursToStart in 0..FAST_THRESHOLD_HOURS) notes += "The event starts within $FAST_THRESHOLD_HOURS hours. FAST mode is built for this."
        if (r.organiserCoversStay) notes += "The organiser covers the stay, so no stay was searched."

        return finish(r, mode, cands, rejected, comparison, notes, pax)
    }

    // ---------- pain point 3 ----------

    private fun planFamilyVisit(input: PlannerInput): PlanResult {
        val r = input.request
        val mode = r.mode
        val family = r.familySize ?: throw ValidationException("FAMILY_VISIT needs familySize")
        val nights = r.nights ?: throw ValidationException("FAMILY_VISIT needs nights")
        val host = r.hostTraveller
        val hostTt = host?.let { input.timetables[it] }
        val rejected = Counter()
        val notes = mutableListOf<String>()
        if (host != null && hostTt == null) notes += "No timetable for Traveller $host: their classes were not checked."
        val b = r.buffers
        val bag = ModeRules.checkedBag(mode)
        val together = family + (if (host != null) 1 else 0)

        val leg1 = input.flights["leg1"].orEmpty().filter { f -> ModeRules.rejectFlight(mode, f, family)?.let { rejected.add("leg 1: $it"); false } ?: true }
        val leg2 = input.flights["leg2"].orEmpty().filter { f -> ModeRules.rejectFlight(mode, f, together)?.let { rejected.add("leg 2: $it"); false } ?: true }
        // The host flies back alone: student rules (one stop allowed, carry-on).
        val leg3 = input.flights["leg3"].orEmpty().filter { f -> ModeRules.rejectFlight(Mode.LOW_COST, f, 1)?.let { rejected.add("leg 3: $it"); false } ?: true }

        val comfortFlight = { f: FlightOffer -> (if (ModeRules.isLateNight(f.arrive)) 1 else 0) }
        val leg1Top = leg1.sortedWith(compareBy({ comfortFlight(it) }, { it.price(bag) })).take(4)
        val stays = leg1Top.map { it.arrive.toLocalDate() }.distinct().associateWith { checkIn ->
            val key = StayKey(checkIn, r.arriveOn!!.plusDays(nights.toLong()))
            input.stays[key].orEmpty().flatMap { s ->
                val (c, reason) = ModeRules.stayOptions(mode, s, family, key.nights, input.minutesToVenue[s.id])
                if (reason != null) rejected.add("stay: $reason"); c
            }.sortedBy { ModeRules.stayScore(mode, it) }.take(3)
        }

        data class Out(val l2: FlightOffer, val l3: FlightOffer?, val away: AwayWindow?, val conflict: Conflict)
        val outs = mutableListOf<Out>()
        for (l2 in leg2) {
            val leave = l2.depart.minusMinutes((b.checkInBeforeDepartureMin + b.campusToAirportMin).toLong())
            if (hostTt == null) { outs += Out(l2, null, null, Conflict(emptyList(), emptyList())); continue }
            if (r.hostReturnOn == null) {
                val c = Windows.check(listOf(hostTt), leave, l2.depart)
                if (c.ok) outs += Out(l2, null, AwayWindow(leave, null), c) else rejected.add("leg 2: breaks a must-attend class")
                continue
            }
            for (l3 in leg3) {
                if (!l3.depart.isAfter(l2.arrive)) continue
                val back = l3.arrive.plusMinutes((b.immigrationOnArrivalMin + b.campusToAirportMin).toLong())
                val c = Windows.check(listOf(hostTt), leave, back)
                if (c.ok) outs += Out(l2, l3, AwayWindow(leave, back), c) else rejected.add("leg 3: breaks a must-attend class")
            }
        }
        if (host != null && r.hostReturnOn == null) notes += "No hostReturnOn given: only the leg-2 departure was checked against Traveller $host's timetable."

        val cands = mutableListOf<Candidate>()
        for (l1 in leg1Top) {
            val stayList = stays[l1.arrive.toLocalDate()].orEmpty()
            if (stayList.isEmpty()) { rejected.add("no stay that meets the $mode rules"); continue }
            for (s in stayList) for (o in outs.sortedWith(compareBy({ it.conflict.canSkipMinutes }, { comfortFlight(it.l2) }, { it.l2.price(bag) + (it.l3?.price(false) ?: 0.0) })).take(4)) {
                val flights = listOfNotNull("leg 1 (family in)" to l1, "leg 2 (everyone home together)" to o.l2, o.l3?.let { "leg 3 (Traveller $host back)" to it })
                val warnings = buildList {
                    flights.forEach { (leg, f) -> if (ModeRules.isLateNight(f.arrive)) add("$leg ${f.flightNos} lands at ${hhmm(f.arrive)} local time (late night)") }
                    s.unknown.forEach { add("stay ${s.name}: $it unknown") }
                }
                val total = money(l1.price(bag) + o.l2.price(bag) + (o.l3?.price(false) ?: 0.0) + s.total)
                cands += Candidate(flights, s, listOfNotNull(o.away?.let { "Traveller $host" to it }).toMap(), o.conflict, warnings, total, together)
            }
        }
        return finish(r, mode, cands, rejected, emptyList(), notes, together)
    }

    // ---------- ranking and output ----------

    private fun finish(r: TripRequest, mode: Mode, cands: List<Candidate>, rejected: Counter, comparison: List<StayComparison>, notes: MutableList<String>, pax: Int): PlanResult {
        val bag = ModeRules.checkedBag(mode)
        val order: Comparator<Candidate> = compareBy<Candidate> { it.conflict.canSkipMinutes }.then(
            when (mode) {
                Mode.FAST -> compareBy<Candidate>({ it.flights.last().second.arrive.toInstant() }, { it.total })
                Mode.LOW_COST -> compareBy({ it.total }, { Duration.between(it.flights.first().second.depart, it.flights.last().second.arrive) })
                Mode.LOW_COST_CONVENIENT -> compareBy({ it.stay?.unknown?.size ?: 0 },
                    { it.total + ModeRules.VALUE_OF_MINUTE * (it.stay?.minutesToVenue ?: 0) * 2 * maxOf(1, it.stay?.nights ?: 1) }, { it.total })
                Mode.FAMILY -> compareBy({ (it.stay?.let(ModeRules::familyComfortPenalty) ?: 0) + it.warnings.count { w -> "late night" in w } * 2 }, { it.total })
            }
        )
        val sorted = cands.sortedWith(order)
        val inBudget = sorted.filter { r.budget == null || it.total <= r.budget }
        sorted.size.minus(inBudget.size).let { n -> repeat(n) { rejected.add("over budget") } }

        val picked = mutableListOf<Candidate>()
        val want = if (mode == Mode.FAST) 2 else 3
        for (c in inBudget) {
            if (picked.size >= want) break
            val ids = c.flights.map { it.second.id }.toSet() + listOfNotNull(c.stay?.stayId)
            val sameFlights = picked.any { p -> p.flights.map { it.second.id }.toSet() + listOfNotNull(p.stay?.stayId) == ids }
            // A backup should not depend on the same outbound flight when another exists.
            val sameFirst = picked.any { p -> p.flights.first().second.flightNos == c.flights.first().second.flightNos }
            if (sameFlights) continue
            if (mode == Mode.FAST && sameFirst && inBudget.any { o -> picked.none { p -> p.flights.first().second.flightNos == o.flights.first().second.flightNos } }) continue
            picked += c
        }
        if (mode == Mode.FAST && picked.size < want) inBudget.firstOrNull { it !in picked }?.let { picked += it }

        fun toPlan(c: Candidate, i: Int) = TripPlan(
            rank = i + 1,
            label = if (mode == Mode.FAST) (if (i == 0) "best" else "backup") else "option ${i + 1}",
            flights = c.flights.map { (leg, f) -> ModeRules.flightChoice(leg, f, if (leg.startsWith("leg 3")) Mode.LOW_COST else mode) },
            stay = c.stay,
            away = c.away,
            costs = CostBreakdown(
                flights = money(c.flights.sumOf { (leg, f) -> f.price(bag && !leg.startsWith("leg 3")) }),
                stay = c.stay?.total ?: 0.0, total = c.total, perPerson = money(c.total / c.pax),
            ),
            skippedClasses = c.conflict.canSkip,
            warnings = c.warnings,
        )
        val plans = picked.mapIndexed { i, c -> toPlan(c, i) }
        if (plans.isNotEmpty() && plans.first().skippedClasses.isNotEmpty())
            notes += "No plan avoids every class. The best plan misses ${plans.first().skippedClasses.sumOf { it.minutesMissed }} minutes of can-skip classes: " +
                plans.first().skippedClasses.joinToString { it.title } + ". No must-attend class is missed."
        val status = when {
            plans.isNotEmpty() -> PlanStatus.OK
            sorted.isNotEmpty() -> PlanStatus.NO_PLAN_IN_BUDGET
            else -> PlanStatus.NO_PLAN
        }
        val overBudget = if (status == PlanStatus.NO_PLAN_IN_BUDGET) toPlan(sorted.first(), 0).copy(label = "cheapest over budget") else null
        val result = PlanResult(status, mode, r.painPoint, plans, rejected.m, comparison, overBudget, notes)
        return result.copy(summary = Explain.summary(result))
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun hhmm(t: ZonedDateTime): String = t.format(hm)
}
