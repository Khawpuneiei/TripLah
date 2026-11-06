package triplah.core

import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalsTest {
    @Test
    fun `risk signal levels and wording`() {
        val good = Risk.signal(FlightHistory("SQ1", 90, 90, 0, 2, 8, false, CarrierType.FULL_SERVICE))
        assertEquals(RiskLevel.LOW, good.level)
        val bad = Risk.signal(FlightHistory("FD9", 90, 82, 8, 25, 2, true, CarrierType.LOW_COST))
        assertEquals(RiskLevel.HIGH, bad.level)
        assertTrue(bad.reasons.any { "last flight of the day" in it })
        assertTrue(bad.disclaimer.startsWith("Risk based on history"))
        assertFalse((bad.reasons + bad.disclaimer).any { "will be cancelled" in it && "not" !in it })
        assertTrue(bad.reasons.none { "seats sold" in it.lowercase() && "not" !in it })
        val thin = Risk.signal(FlightHistory("XX1", 30, 3, 0, 0, 5, false, CarrierType.FULL_SERVICE))
        assertTrue(thin.reasons.any { "weak" in it })
    }

    @Test
    fun `buy or wait is a labelled estimate`() {
        val hist = listOf(100.0, 110.0, 120.0, 130.0, 140.0)
        val today = LocalDate.parse("2026-11-06")
        assertEquals(BuyAdvice.BUY_NOW, Pricing.buyOrWait(100.0, hist, today, today.plusDays(60), null).advice)
        assertEquals(BuyAdvice.WAIT, Pricing.buyOrWait(150.0, hist, today, today.plusDays(60), null).advice)
        assertEquals(BuyAdvice.BUY_NOW, Pricing.buyOrWait(150.0, hist, today, today.plusDays(10), null).advice, "close to departure")
        assertEquals(BuyAdvice.BUY_NOW, Pricing.buyOrWait(150.0, hist, today, today.plusDays(60), today).advice, "book-by date reached")
        assertEquals(BuyAdvice.NO_DATA, Pricing.buyOrWait(150.0, listOf(1.0), today, today.plusDays(60), null).advice)
        assertTrue(Pricing.buyOrWait(150.0, hist, today, today.plusDays(60), null).label.startsWith("Estimate"))
        assertEquals(125.0, Pricing.median(listOf(100.0, 120.0, 130.0, 150.0)))
    }

    @Test
    fun `split is exact to the cent`() {
        val r = Split.split(listOf(SplitItem("room", 100.0, listOf("A", "B", "C")), SplitItem("A flight", 50.0, listOf("A"))))
        assertEquals(83.34, r.perPerson["A"]); assertEquals(33.33, r.perPerson["B"]); assertEquals(150.0, r.total)
        assertEquals(r.total, r.perPerson.values.sum(), 0.001)
        assertFailsWith<ValidationException> { Split.split(listOf(SplitItem("x", 1.0, emptyList()))) }
    }

    @Test
    fun `recheck polling schedule`() {
        val dep = at("2026-12-01", "10:00")
        assertEquals(at("2026-11-11", "10:00"), Recheck.nextCheck(at("2026-11-10", "10:00"), dep), "daily")
        assertEquals(at("2026-11-28", "16:00"), Recheck.nextCheck(at("2026-11-28", "10:00"), dep), "6-hourly in the last week")
        assertEquals(at("2026-11-30", "21:00"), Recheck.nextCheck(at("2026-11-30", "20:00"), dep), "hourly in the last 24 hours")
        assertEquals(null, Recheck.nextCheck(at("2026-12-01", "11:00"), dep))
    }

    @Test
    fun `recheck names the class an inbound delay now clashes with`() {
        val w = WatchedFlight("TR3", LocalDate.parse("2026-11-20"), "BKK", "SIN", at("2026-11-20", "20:30", ICT), at("2026-11-20", "23:50"), "inbound", listOf("A"))
        val a = tt("A", ev("SC2104 LAB", at("2026-11-21", "02:00"), 2, Attendance.MUST_ATTEND))
        fun status(min: Long, state: FlightState = FlightState.RETIMED) = FlightStatus("TR3", w.date, state, w.depart, w.arrive, w.depart.plusMinutes(min), w.arrive.plusMinutes(min), "test", "now")
        assertFalse(Recheck.evaluate(w, status(10), listOf(a), Buffers()).changed, "under 15 minutes is not a change")
        val moved = Recheck.evaluate(w, status(120), listOf(a), Buffers())
        assertTrue(moved.changed); assertEquals(120, moved.moveMinutes)
        assertEquals(listOf("SC2104 LAB"), moved.clashes.map { it.title })
        assertTrue("SC2104 LAB" in moved.message)
        assertTrue(Recheck.evaluate(w, status(0, FlightState.CANCELLED), listOf(a), Buffers()).cancelled)
    }

    @Test
    fun `grounding finds invented prices, times and flight numbers`() {
        val tool = Json.parseToJsonElement("""{"flights":[{"flightNos":"TR612","depart":"2026-11-20T07:05+08:00[Asia/Singapore]","price":182.4}],"total":312.4}""")
        assertTrue(Grounding.check("Take TR 612 at 07:05, total SGD 312.40.", listOf(tool)).ok)
        val bad = Grounding.check("Take TR613 at 07:15, total SGD 299.00.", listOf(tool))
        assertEquals(listOf("299.00"), bad.ungroundedPrices)
        assertEquals(listOf("07:15"), bad.ungroundedTimes)
        assertEquals(listOf("TR613"), bad.ungroundedFlights)
    }

    @Test
    fun `validation`() {
        val known = setOf("SIN", "BKK")
        assertEquals("SIN", Validate.airport(" sin ", known))
        assertFailsWith<ValidationException> { Validate.airport("SINGAPORE", known) }
        assertFailsWith<ValidationException> { Validate.airport("LHR", known) }
        assertFailsWith<ValidationException> { Validate.pax(0) }
        assertFailsWith<ValidationException> { Validate.pax(10) }
        assertFailsWith<ValidationException> { Validate.budget(-5.0) }
        assertFailsWith<ValidationException> { Validate.budget(1e9) }
        assertFailsWith<ValidationException> { Validate.traveller("Somchai Jaidee") }
        assertFailsWith<ValidationException> { Validate.noIdNumbers("passport E1234567X", "notes") }
        assertEquals("B", Validate.traveller("Traveller B"))
        assertFailsWith<ValidationException> { parseZdt("2026-11-20T08:15") }
    }
}
