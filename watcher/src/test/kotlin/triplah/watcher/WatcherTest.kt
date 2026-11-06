package triplah.watcher

import kotlinx.coroutines.runBlocking
import triplah.api.CheckFlightIn
import triplah.api.ImportTimetableIn
import triplah.api.WatchPriceIn
import triplah.api.Wiring
import triplah.providers.FlightQuery
import triplah.providers.MockStatusProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatcherTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC)
    private val svc = Wiring.mock(clock = clock)
    private val sent = mutableListOf<String>()
    private val watcher = Watcher(svc, listOf(Alerter { kind, msg -> sent += "$kind: $msg" }))
    private val sgt = ZoneId.of("Asia/Singapore")

    @Test
    fun `flight recheck - 2 hour move names the clashing class, then cancellation, with alternatives`() {
        val date = LocalDate.parse("2026-11-20")
        val f = runBlocking { svc.hub.searchFlights(FlightQuery("BKK", "SIN", date, 1, 0)).items }.filter { it.arrive.hour in 21..23 }.maxBy { it.arrive.toInstant() }
        val lab = f.arrive.plusMinutes(45 + 60 + 30)
        svc.importTimetable(ImportTimetableIn("A", format = "json", content = """{"events":[{"title":"EE4001 Night Lab","start":"${lab.toOffsetDateTime()}","end":"${lab.plusHours(2).toOffsetDateTime()}"}]}"""))
        val watch = runBlocking { svc.checkFlight(CheckFlightIn(f.flightNos, date, leg = "inbound", travellers = listOf("A"), watch = true)) }
        assertTrue(watch.watchId != null)

        // 10 days out: daily checks. check_flight just checked it, so the next check is tomorrow.
        val t0 = ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, sgt)
        assertEquals(0, watcher.runOnce(t0).flightChecks)
        assertEquals(1, watcher.runOnce(t0.plusDays(1)).flightChecks)
        assertTrue(sent.isEmpty(), "nothing changed")
        assertEquals(0, watcher.runOnce(t0.plusDays(1).plusHours(2)).flightChecks, "not due again until the next day")

        val status = svc.hub.status as MockStatusProvider
        status.moveBy(f.flightNos, date, 120)
        val r1 = watcher.runOnce(t0.plusDays(2))
        assertEquals(1, r1.alerts.size)
        assertTrue("EE4001 Night Lab" in r1.alerts[0], r1.alerts[0])
        assertTrue("Alternatives:" in r1.alerts[0], r1.alerts[0])

        status.cancel(f.flightNos, date)
        val r2 = watcher.runOnce(t0.plusDays(3))
        assertTrue(sent.last().startsWith("cancelled:"), sent.last())
        assertTrue("Alternatives:" in r2.alerts.single())
        assertEquals(0, watcher.runOnce(t0.plusDays(4)).flightChecks, "a cancelled flight is no longer polled")
        assertEquals(2, svc.store.alerts().size)
        assertTrue(svc.store.flightRecords(f.flightNos).any { it.second == "CANCELLED" }, "own history feeds the risk signal")
    }

    @Test
    fun `price watch - checks at most twice a day, alerts below target and on the book-by date`() {
        val date = LocalDate.parse("2026-12-18")
        val out = runBlocking { svc.watchPrice(WatchPriceIn("SIN", "BKK", date, 1, targetPrice = 1.0, bookBy = LocalDate.parse("2026-12-01"))) }
        val t0 = ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, sgt)
        assertEquals(1, watcher.runOnce(t0).priceChecks)
        assertEquals(0, watcher.runOnce(t0.plusHours(6)).priceChecks, "twice a day at most")
        assertTrue(sent.isEmpty(), "price is above target")
        val r = watcher.runOnce(ZonedDateTime.of(2026, 12, 1, 9, 0, 0, 0, sgt))
        assertTrue(r.alerts.single().startsWith("Book-by date"), r.alerts.toString())
        assertEquals("DONE", svc.store.priceWatches(activeOnly = false).single { it.id == out.watch.id }.status)

        runBlocking { svc.watchPrice(WatchPriceIn("SIN", "BKK", date, 1, targetPrice = 10_000.0, bookBy = LocalDate.parse("2026-12-10"))) }
        val hit = watcher.runOnce(ZonedDateTime.of(2026, 12, 2, 9, 0, 0, 0, sgt))
        assertTrue(hit.alerts.single().contains("below your target"))
    }
}
