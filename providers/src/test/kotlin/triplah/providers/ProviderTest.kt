package triplah.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import triplah.core.FlightOffer
import triplah.core.FlightState
import triplah.core.RoomKind
import triplah.core.StayType
import triplah.providers.real.AeroDataBoxStatus
import triplah.providers.real.LiteApiStays
import triplah.providers.real.OneMapTransit
import triplah.providers.real.SerpApiFlights
import triplah.providers.real.TravelpayoutsCalendar
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun recorded(name: String) = ProviderTest::class.java.getResource("/recorded/$name")!!.readText()

class ProviderTest {
    private val gw = HttpGateway(HttpGateway.DEFAULT_HOSTS, QuotaGuard(emptyMap()))
    private val clock = Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `serpapi google flights - direct and one-stop, local times with zones`() {
        val offers = SerpApiFlights(gw, "k", clock).parse(recorded("serpapi_google_flights.json"), 1)
        assertEquals(2, offers.size, "the empty itinerary is skipped")
        val d = offers.first()
        assertEquals("TR612", d.flightNos); assertEquals(0, d.stops); assertEquals(182.0, d.priceCarryOn)
        assertEquals("+08:00", d.depart.offset.id); assertEquals("+07:00", d.arrive.offset.id)
        assertEquals(145, d.duration.toMinutes())
        assertEquals("AK700+AK880", offers[1].flightNos); assertEquals(1, offers[1].stops)
        assertTrue("checked-bag fee not included" in d.source)
    }

    @Test
    fun `travelpayouts calendar`() {
        val c = TravelpayoutsCalendar(gw, "t", clock).parse(recorded("travelpayouts_prices_for_dates.json"), "SIN", "BKK")
        assertEquals(3, c.size)
        assertEquals(LocalDate.parse("2026-11-20"), c[0].date); assertEquals(96.0, c[0].pricePerSeat); assertEquals("TR612", c[0].flightNo)
    }

    @Test
    fun `liteapi - room size, lift and MRT are unknown, capsule guessed from the name, html stripped`() {
        val api = LiteApiStays(gw, "k", clock)
        val hotels = api.parseHotels(recorded("liteapi_hotels.json"))
        val q = StayQuery("Singapore", LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-22"), 1)
        val stays = api.parseRates(recorded("liteapi_rates.json"), hotels, q)
        assertEquals(2, stays.size)
        val pod = stays.first { it.id == "lp1a2b3" }
        assertEquals(StayType.CAPSULE, pod.type); assertEquals(RoomKind.POD, pod.rooms.single().kind)
        assertEquals(48.0, pod.rooms.single().pricePerNight, "total / nights")
        assertNull(pod.hasLift); assertNull(pod.mrtWalkMin); assertNull(pod.rooms.single().sizeM2)
        assertTrue("<p>" !in pod.description)
        assertEquals(RoomKind.FAMILY_ROOM, stays.first { it.id == "lp9z8y7" }.rooms.single().kind)
    }

    @Test
    fun `aerodatabox - revised time means retimed`() {
        val s = AeroDataBoxStatus(gw, "k", clock).parse(recorded("aerodatabox_flight.json"), "TR612", LocalDate.parse("2026-11-20"))
        assertEquals(FlightState.RETIMED, s.state)
        assertEquals(7, s.scheduledDepart.hour); assertEquals(9, s.estimatedDepart!!.hour)
        assertEquals("+07:00", s.scheduledArrive.offset.id)
    }

    @Test
    fun `onemap route minutes`() {
        assertEquals(38, OneMapTransit(gw, "t").parse(recorded("onemap_route.json")))
        assertTrue(runCatching { runBlocking { OneMapTransit(gw, "t").minutes(13.7, 100.5, 13.8, 100.6, "Bangkok") } }.exceptionOrNull() is ProviderUnavailable)
    }

    @Test
    fun `mock fares - a group on one booking can pay more per seat than one person`() {
        val p = MockFlightProvider(SeedData.default(), clock)
        val date = LocalDate.parse("2026-12-09")
        val one = runBlocking { p.search(FlightQuery("SIN", "BKK", date, 1, 0)) }.associateBy { it.flightNos }
        val four = runBlocking { p.search(FlightQuery("SIN", "BKK", date, 4, 0)) }.associateBy { it.flightNos }
        val perSeat = four.mapNotNull { (no, o) -> one[no]?.let { o.priceWithBag / 4 to it.priceWithBag } }
        assertTrue(perSeat.all { (g, s) -> g >= s - 0.01 })
        assertTrue(perSeat.any { (g, s) -> g > s + 0.01 }, "at least one flight has fewer than 4 cheapest seats")
        // Deterministic.
        assertEquals(one.values.map { it.priceWithBag }, runBlocking { p.search(FlightQuery("SIN", "BKK", date, 1, 0)) }.map { it.priceWithBag })
    }

    @Test
    fun `hub - parallel providers, timeout, cache`() = runBlocking {
        val slow = object : FlightProvider {
            override val name = "slow"
            override suspend fun search(q: FlightQuery): List<FlightOffer> { delay(10_000); return emptyList() }
        }
        val seed = SeedData.default()
        val hub = ProviderHub(listOf(MockFlightProvider(seed, clock), slow), MockStayProvider(seed, clock), MockStatusProvider(seed, clock), MockTransitProvider(), clock, timeoutMs = 300)
        val t = System.currentTimeMillis()
        val r = hub.searchFlights(FlightQuery("SIN", "BKK", LocalDate.parse("2026-11-20"), 1, 0))
        assertTrue(System.currentTimeMillis() - t < 3_000, "the slow provider is cut off")
        assertTrue(r.items.isNotEmpty())
        assertTrue(r.errors.single().contains("timed out"))
        val calls = hub.providerCalls
        hub.searchFlights(FlightQuery("SIN", "BKK", LocalDate.parse("2026-11-20"), 1, 0))
        assertEquals(calls, hub.providerCalls, "second search is served from the cache")
    }
}
