package triplah.providers

import triplah.core.FlightState
import triplah.providers.real.AeroDataBoxStatus
import triplah.providers.real.OneMapTransit
import triplah.providers.real.SerpApiFlights
import triplah.providers.real.TravelpayoutsCalendar
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real responses recorded on 6 Nov 2026 with `./gradlew :evals:liveCheck` (TRIPLAH_RECORD_DIR), keys redacted.
 * SIN -> BKK on 18 Dec 2026. These replace the hand-written fixtures as the main parser tests; the hand-written
 * ones stay for edge cases the live data did not contain (one-stop itineraries, an empty itinerary).
 */
class LiveRecordingTest {
    private fun live(name: String) = LiveRecordingTest::class.java.getResource("/recorded/${name}_live.json")!!.readText()
    private val gw = HttpGateway(HttpGateway.DEFAULT_HOSTS, QuotaGuard(emptyMap()))
    private val clock = Clock.fixed(Instant.parse("2026-11-06T13:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `serpapi - real Google Flights response`() {
        val offers = SerpApiFlights(gw, "k", clock).parse(live("serpapi"), 1)
        assertTrue(offers.size >= 5, "${offers.size} offers")
        assertTrue(offers.all { it.segments.first().from == "SIN" && it.segments.last().to == "BKK" })
        assertTrue(offers.all { it.depart.toLocalDate() == LocalDate.parse("2026-12-18") })
        assertTrue(offers.all { it.depart.offset.id == "+08:00" && it.arrive.offset.id == "+07:00" })
        // SIN-BKK is about 2.5 hours; local times differ by 1 hour.
        assertTrue(offers.filter { it.stops == 0 }.all { it.duration.toMinutes() in 120..180 }, offers.map { it.duration }.toString())
        assertTrue(offers.all { (it.priceCarryOn ?: 0.0) > 50 })
        assertTrue(offers.any { it.flightNos.startsWith("TR") } && offers.any { it.flightNos.startsWith("TG") })
    }

    @Test
    fun `travelpayouts - real calendar response`() {
        val c = TravelpayoutsCalendar(gw, "t", clock).parse(live("travelpayouts"), "SIN", "BKK")
        assertTrue(c.size >= 10)
        assertTrue(c.all { it.date.year == 2026 && it.date.monthValue == 12 && it.pricePerSeat > 0 })
    }

    @Test
    fun `aerodatabox - real status response`() {
        val s = AeroDataBoxStatus(gw, "k", clock).parse(live("aerodatabox"), "TR610", LocalDate.parse("2026-12-18"))
        assertTrue(s.state in setOf(FlightState.SCHEDULED, FlightState.RETIMED, FlightState.UNKNOWN), s.state.name)
        assertEquals(16, s.scheduledDepart.hour)
        assertEquals("+08:00", s.scheduledDepart.offset.id)
        assertEquals("+07:00", s.scheduledArrive.offset.id)
    }

    @Test
    fun `liteapi - real hotels near True Digital Park, capsules and hostels by hotel type, facilities mapped`() {
        val api = triplah.providers.real.LiteApiStays(gw, "k", clock)
        val hotels = api.parseHotels(live("liteapi_hotels")) + api.parseHotels(live("liteapi_hotels_budget"))
        val q = StayQuery("Bangkok", LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-22"), 1, 13.6851, 100.6110)
        val stays = api.parseRates(live("liteapi_rates"), hotels, q)
        assertTrue(stays.size >= 30, "${stays.size} stays")
        val budget = stays.filter { it.type == triplah.core.StayType.HOSTEL || it.type == triplah.core.StayType.CAPSULE }
        assertTrue(budget.size >= 5, "${budget.size} hostels/capsules")
        assertTrue(budget.any { s -> s.rooms.any { it.kind == triplah.core.RoomKind.POD || it.kind == triplah.core.RoomKind.DORM_BED } })
        assertTrue(stays.any { it.lateCheckIn == true } && stays.any { it.lockers == true } && stays.any { it.hasLift == true })
        // LiteAPI never gives room size or MRT walk: those stay unknown, never false.
        assertTrue(stays.all { s -> s.mrtWalkMin == null && s.rooms.all { it.sizeM2 == null } })
        assertTrue(stays.none { it.lateCheckIn == false || it.lockers == false })
    }

    @Test
    fun `liteapi - room kinds from real rate names`() {
        val k = { n: String, t: triplah.core.StayType -> triplah.providers.real.LiteApiStays.roomKind(n, t, 1) }
        val H = triplah.core.StayType.HOSTEL; val C = triplah.core.StayType.CAPSULE
        assertEquals(triplah.core.RoomKind.POD, k("Single Capsule - Mixed", C))
        assertEquals(triplah.core.RoomKind.POD, k("Bed In 12 Capsule Bed", H))
        assertEquals(triplah.core.RoomKind.DORM_BED, k("1 Bed in 12 Beds Mixed Room , 1 Twin Bunk Bed", H))
        assertEquals(triplah.core.RoomKind.DORM_BED, k("Upper Decker Mixed Room - Common", H))
        assertEquals(triplah.core.RoomKind.DORM_BED, k("Bed in Bunk Bed (Mixed Gender)", H))
        assertEquals(triplah.core.RoomKind.FAMILY_ROOM, k("Family Room with Bunk Bed", triplah.core.StayType.HOTEL))
        assertEquals(triplah.core.RoomKind.PRIVATE_DOUBLE, k("Budget Double Room", H))
        assertEquals(triplah.core.RoomKind.PRIVATE_DOUBLE, k("Single Room A-Non-Smoking", H))
        assertEquals(triplah.core.RoomKind.PRIVATE_DOUBLE, k("Private Bunk Bed with Private External Bathroom", H))
        assertEquals(triplah.core.RoomKind.PRIVATE_TWIN, k("Deluxe Twin Room", triplah.core.StayType.HOTEL))
    }

    @Test
    fun `onemap - real route NTU to Changi`() {
        val m = OneMapTransit(gw, "t").parse(live("onemap"))
        assertTrue(m in 60..150, "$m min")
    }
}
