package triplah.api

import kotlinx.coroutines.runBlocking
import triplah.core.*
import triplah.providers.FlightQuery
import triplah.providers.MockStatusProvider
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PainPointTest {
    private val svc = seededService()

    private fun plan(r: TripRequest) = runBlocking { svc.planTrip(r) }

    private fun assertNoMustAttendBroken(result: PlanResult) {
        val tts = svc.store.timetables()
        for (p in result.plans) for ((who, away) in p.away) {
            val tt = tts.getValue(who.removePrefix("Traveller "))
            val back = away.backOnCampus ?: continue
            assertTrue(Windows.check(listOf(tt), away.leaveCampus, back).ok, "plan ${p.rank} breaks a must-attend class for $who")
        }
    }

    @Test
    fun `pain point 1 - one-day award ceremony in Bangkok, LOW_COST and FAST`() {
        val ev = TripEvent("Award ceremony", parseZdt("2026-11-20T14:00+07:00"), parseZdt("2026-11-20T17:00+07:00"), "th-qsncc")
        val low = plan(TripRequest(mode = Mode.LOW_COST, origin = "SIN", destination = "BKK", travellers = listOf("A"), event = ev))
        assertEquals(PlanStatus.OK, low.status)
        assertNoMustAttendBroken(low)
        // The ceremony is on a Friday, so Friday lectures are missed. They are can-skip and named.
        assertTrue(low.notes.any { "can-skip" in it })
        assertTrue(low.plans.zipWithNext().all { (a, b) -> a.costs.total <= b.costs.total || a.skippedClasses.sumOf { it.minutesMissed } < b.skippedClasses.sumOf { it.minutesMissed } })
        // Thursday's must-attend lab ends 16:30, so no outbound flight leaves campus before then.
        assertTrue(low.plans.all { p -> p.away.values.all { it.leaveCampus.isAfter(parseZdt("2026-11-19T16:30+08:00")) } })

        val fast = plan(TripRequest(mode = Mode.FAST, origin = "SIN", destination = "BKK", travellers = listOf("A"), event = ev))
        assertEquals(listOf("best", "backup"), fast.plans.map { it.label })
        assertTrue(fast.plans.all { p -> p.flights.all { it.stops == 0 } })
        assertTrue(fast.plans[0].flights[0].flightNos != fast.plans[1].flights[0].flightNos, "backup uses a different outbound flight")
        assertNoMustAttendBroken(fast)
    }

    @Test
    fun `pain point 2 - hackathon with two timetables, capsule near the venue, pods vs twin, split cost`() {
        val ev = TripEvent("Hackathon", parseZdt("2026-11-21T09:00+07:00"), parseZdt("2026-11-22T15:00+07:00"), "th-tdp")
        val r = plan(TripRequest(mode = Mode.LOW_COST_CONVENIENT, origin = "SIN", destination = "BKK", travellers = listOf("A", "B"), event = ev))
        assertEquals(PlanStatus.OK, r.status)
        assertNoMustAttendBroken(r)
        val p = r.plans.first()
        assertEquals(2, p.away.size, "both timetables were checked")
        assertTrue(p.flights.all { it.pax == 2 })
        val stay = assertNotNull(p.stay)
        assertTrue(stay.type in setOf(StayType.CAPSULE, StayType.HOSTEL) && stay.minutesToVenue!! <= 15)
        assertEquals(2, r.stayComparison.size, "2 pods compared with 1 private twin")
        assertEquals(p.costs.total / 2, p.costs.perPerson, 0.01)

        val split = svc.splitBudget(SplitIn(listOf(
            SplitItem("flights", p.costs.flights, listOf("Traveller A", "Traveller B")),
            SplitItem("stay", p.costs.stay, listOf("Traveller A", "Traveller B")),
        )))
        assertEquals(p.costs.total, split.perPerson.values.sum(), 0.011)

        val covered = plan(TripRequest(mode = Mode.LOW_COST_CONVENIENT, origin = "SIN", destination = "BKK", travellers = listOf("A", "B"), event = ev, organiserCoversStay = true))
        assertTrue(covered.plans.all { it.stay == null })
    }

    @Test
    fun `pain point 3 - family visits Singapore and everyone flies home together on one booking`() {
        val r = plan(TripRequest(painPoint = PainPoint.FAMILY_VISIT, mode = Mode.FAMILY, origin = "BKK", destination = "SIN", familySize = 3,
            arriveOn = LocalDate.parse("2026-12-05"), nights = 4, hostTraveller = "A", hostReturnOn = LocalDate.parse("2026-12-12")))
        assertEquals(PlanStatus.OK, r.status)
        val p = r.plans.first()
        assertEquals(3, p.flights.size)
        assertEquals(3, p.flights[0].pax, "leg 1: the family")
        assertEquals(4, p.flights[1].pax, "leg 2: family + host on one booking")
        assertEquals(1, p.flights[2].pax, "leg 3: host back to campus")
        assertTrue(p.flights.take(2).all { it.stops == 0 && !it.redEye && it.checkedBag })
        val stay = assertNotNull(p.stay)
        assertEquals(4, stay.nights)
        assertTrue(stay.roomKind == RoomKind.FAMILY_ROOM || stay.roomKind == RoomKind.SUITE || (stay.sizeM2 ?: 99.0) >= 25)
        assertTrue(stay.hasLift != false && (stay.mrtWalkMin ?: 0) <= 10)
        assertNoMustAttendBroken(r)
    }

    @Test
    fun `recheck - flight moves 2 hours, then is cancelled - alert names the class and offers an alternative`() {
        val date = LocalDate.parse("2026-11-20")
        val inbound = runBlocking { svc.hub.searchFlights(FlightQuery("BKK", "SIN", date, 1, 0)).items }
            .filter { it.arrive.hour in 21..23 }.maxBy { it.arrive.toInstant() }
        // A must-attend night lab 30 minutes after A is back on campus.
        val labStart = inbound.arrive.plusMinutes(45 + 60 + 30)
        svc.importTimetable(ImportTimetableIn("C", format = "json", content = """{"zone":"Asia/Singapore","events":[
            {"title":"EE4001 Night Lab","start":"${labStart.toOffsetDateTime()}","end":"${labStart.plusHours(2).toOffsetDateTime()}"}]}"""))
        val status = svc.hub.status as MockStatusProvider
        val no = inbound.flightNos

        val onTime = runBlocking { svc.checkFlight(CheckFlightIn(no, date, leg = "inbound", travellers = listOf("C"))) }
        assertEquals(false, onTime.recheck!!.changed)

        status.moveBy(no, date, 120)
        val moved = runBlocking { svc.checkFlight(CheckFlightIn(no, date, leg = "inbound", travellers = listOf("C"))) }
        assertTrue(moved.recheck!!.changed)
        assertEquals(listOf("EE4001 Night Lab"), moved.recheck!!.clashes.map { it.title })
        assertTrue("EE4001 Night Lab" in moved.recheck!!.message)
        assertTrue(moved.alternatives.isNotEmpty(), "an alternative that lands before the lab")
        assertTrue(moved.alternatives.all { it.arrive.plusMinutes(105).isBefore(labStart) || it.arrive.plusMinutes(105) == labStart })

        status.cancel(no, date)
        val cancelled = runBlocking { svc.checkFlight(CheckFlightIn(no, date, leg = "inbound", travellers = listOf("C"))) }
        assertTrue(cancelled.recheck!!.cancelled)
        assertTrue(cancelled.alternatives.isNotEmpty())
    }

    @Test
    fun `risk signal and price watch`() {
        val no = svc.seed.schedules.first { it.from == "SIN" && it.to == "BKK" }.flightNo
        val risk = runBlocking { svc.flightRisk(FlightRiskIn(no)) }
        assertTrue(risk.disclaimer.startsWith("Risk based on history"))
        assertTrue(risk.reasons.isNotEmpty())
        val levels = svc.seed.schedules.map { runBlocking { svc.flightRisk(FlightRiskIn(it.flightNo)) }.level }.toSet()
        assertEquals(RiskLevel.entries.toSet(), levels, "seed data covers Low, Medium and High")

        val w = runBlocking { svc.watchPrice(WatchPriceIn("SIN", "BKK", LocalDate.parse("2026-12-18"), 1, 80.0, LocalDate.parse("2026-12-10"))) }
        assertEquals("ACTIVE", w.watch.status)
        assertNotNull(w.currentPrice)
        assertTrue(w.advice.label.startsWith("Estimate"))
    }

    @Test
    fun `price calendar finds the cheapest free window`() {
        val r = runBlocking { svc.priceCalendar(PriceCalendarIn("SIN", "BKK", listOf("A", "B"), LocalDate.parse("2026-11-12"), LocalDate.parse("2026-12-20"), nights = 2)) }
        assertTrue(r.options.isNotEmpty())
        assertTrue(r.options.zipWithNext().all { (a, b) -> a.estimatedTotal <= b.estimatedTotal })
        val tts = svc.store.timetables().values.toList()
        // Each option's dates fall inside a window with no must-attend events.
        r.options.forEach { o -> assertTrue(Windows.check(tts, o.window.start, o.window.end).ok) }
    }
}
