package triplah.providers

import triplah.core.FlightHistory
import triplah.core.FlightOffer
import triplah.core.FlightState
import triplah.core.FlightStatus
import triplah.core.Segment
import triplah.core.StayOffer
import triplah.core.money
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable pseudo-random number in [0, 1) for a key. Same key, same number, every run. */
internal fun unit(key: String): Double {
    var h = 1125899906842597L
    for (c in key) h = 31 * h + c.code
    h = h xor (h ushr 29); h *= -0x4b47d5b1b6f7a3fbL; h = h xor (h ushr 32)
    return (abs(h) % 1_000_000) / 1_000_000.0
}

/**
 * Seed flights. Prices follow a simple fare-bucket model:
 * a booking for N seats is priced at the lowest bucket that still has N seats, so a group can pay more per seat than one person.
 */
class MockFlightProvider(private val seed: SeedData = SeedData.default(), private val clock: Clock = Clock.systemUTC()) : FlightProvider {
    override val name = "mock-flights"
    private val zones = seed.airports.associate { it.code to ZoneId.of(it.tz) }

    fun runsOn(s: Schedule, date: LocalDate) = date.dayOfWeek.value in s.days

    fun segment(s: Schedule, date: LocalDate): Segment {
        val dep = ZonedDateTime.of(date, LocalTime.parse(s.departLocal), zones.getValue(s.from))
        val arr = dep.plusMinutes(s.blockMin.toLong()).withZoneSameInstant(zones.getValue(s.to))
        return Segment(s.flightNo, s.airline, s.carrierType, s.from, s.to, dep, arr)
    }

    private val buckets = listOf(1.0, 1.22, 1.5, 1.85, 2.4)

    /** Per-seat price and seats left at that price for [pax] seats on one booking. Null when sold out. */
    fun seatPrice(s: Schedule, date: LocalDate, pax: Int): Pair<Double, Int>? {
        val today = LocalDate.now(clock)
        val days = ChronoUnit.DAYS.between(today, date)
        val lead = when { days < 3 -> 1.8; days < 7 -> 1.45; days < 14 -> 1.2; days < 30 -> 1.08; days < 60 -> 1.0; else -> 0.95 }
        val hour = LocalTime.parse(s.departLocal).hour
        val tod = when { hour < 6 -> 0.78; hour < 8 -> 0.92; hour in 17..20 -> 1.12; else -> 1.0 }
        val dow = when (date.dayOfWeek) { DayOfWeek.FRIDAY, DayOfWeek.SUNDAY -> 1.15; DayOfWeek.SATURDAY -> 1.05; else -> 1.0 }
        val noise = 0.9 + 0.25 * unit("${s.flightNo}|$date|p")
        val key = "${s.flightNo}|$date"
        for ((i, mult) in buckets.withIndex()) {
            val seats = when (i) {
                0 -> (unit("$key|b0") * 7).toInt()          // 0..6 cheapest seats
                1 -> 2 + (unit("$key|b1") * 10).toInt()
                2 -> 4 + (unit("$key|b2") * 14).toInt()
                3 -> 6 + (unit("$key|b3") * 20).toInt()
                else -> 9
            }
            if (seats >= pax) return money(s.baseFare * mult * lead * tod * dow * noise) to seats
        }
        return null
    }

    private fun offer(segs: List<Pair<Schedule, Segment>>, date: LocalDate, pax: Int, singleTicket: Boolean): FlightOffer? {
        var carry = 0.0; var bag = 0.0; var seats = Int.MAX_VALUE
        var fullService = false
        for ((s, seg) in segs) {
            val (p, left) = seatPrice(s, seg.depart.toLocalDate(), pax) ?: return null
            carry += p * pax; bag += (p + s.bagFee) * pax; seats = minOf(seats, left)
            if (s.bagFee == 0.0) fullService = true
        }
        if (segs.size > 1 && singleTicket) { carry *= 0.9; bag *= 0.9 }
        val ids = segs.joinToString("-") { it.second.flightNo }
        return FlightOffer(
            id = "mock:$ids:$date:$pax",
            segments = segs.map { it.second },
            pax = pax,
            priceCarryOn = if (fullService) null else money(carry),
            priceWithBag = money(bag),
            seatsLeft = seats,
            singleTicket = singleTicket,
            bookingLink = "https://example.test/book?f=$ids&d=$date&n=$pax",
            source = name,
            fetchedAt = clock.instant(),
        )
    }

    override suspend fun search(q: FlightQuery): List<FlightOffer> {
        val direct = seed.schedules.filter { it.from == q.from && it.to == q.to && runsOn(it, q.date) }
            .mapNotNull { offer(listOf(it to segment(it, q.date)), q.date, q.pax, true) }
        if (q.maxStops < 1) return direct
        val oneStop = mutableListOf<FlightOffer>()
        for (s1 in seed.schedules) {
            if (s1.from != q.from || s1.to == q.to || !runsOn(s1, q.date)) continue
            val seg1 = segment(s1, q.date)
            for (s2 in seed.schedules) {
                if (s2.from != s1.to || s2.to != q.to) continue
                for (d2 in listOf(q.date, q.date.plusDays(1))) {
                    if (!runsOn(s2, d2)) continue
                    val seg2 = segment(s2, d2)
                    val gap = Duration.between(seg1.arrive, seg2.depart).toMinutes()
                    if (gap !in 70..300) continue
                    val single = s1.airline == s2.airline
                    offer(listOf(s1 to seg1, s2 to seg2), q.date, q.pax, single)?.let { oneStop += it }
                }
            }
        }
        return direct + oneStop
    }

    override suspend fun calendar(from: String, to: String, start: LocalDate, end: LocalDate): List<CalendarPrice> {
        val out = mutableListOf<CalendarPrice>()
        var d = start
        while (!d.isAfter(end)) {
            val best = seed.schedules.filter { it.from == from && it.to == to && runsOn(it, d) }
                .mapNotNull { s -> seatPrice(s, d, 1)?.let { s to it.first } }.minByOrNull { it.second }
            if (best != null) {
                // Cached prices: up to 7 days old, as with the real calendar API.
                val age = (unit("$from$to$d|age") * 7).toLong()
                out += CalendarPrice(from, to, d, best.second, best.first.flightNo, "mock-calendar", clock.instant().minus(Duration.ofDays(age)))
            }
            d = d.plusDays(1)
        }
        return out
    }
}

class MockStayProvider(private val seed: SeedData = SeedData.default(), private val clock: Clock = Clock.systemUTC()) : StayProvider {
    override val name = "mock-stays"
    /** Test hook: override a stay's description, e.g. to inject instructions. */
    val descriptionOverrides = ConcurrentHashMap<String, String>()

    override suspend fun search(q: StayQuery): List<StayOffer> {
        val nights = ChronoUnit.DAYS.between(q.checkIn, q.checkOut).toInt()
        if (nights <= 0) return emptyList()
        return seed.stays.filter { it.city.equals(q.city, true) }.map { s ->
            // Average nightly price over the stay: Friday and Saturday nights cost 20% more.
            val factor = (0 until nights).map { q.checkIn.plusDays(it.toLong()).dayOfWeek }
                .sumOf { if (it == DayOfWeek.FRIDAY || it == DayOfWeek.SATURDAY) 1.2 else 1.0 } / nights
            StayOffer(
                id = s.id, name = s.name, city = s.city, type = s.type, lat = s.lat, lon = s.lon,
                rooms = s.rooms.map { it.copy(pricePerNight = money(it.pricePerNight * factor)) },
                lateCheckIn = s.lateCheckIn, lockers = s.lockers, hasLift = s.hasLift, mrtWalkMin = s.mrtWalkMin,
                description = descriptionOverrides[s.id] ?: s.description,
                bookingLink = "https://example.test/stay?id=${s.id}&in=${q.checkIn}&out=${q.checkOut}",
                source = name, fetchedAt = clock.instant(),
            )
        }
    }
}

/** Scriptable flight status: tests move or cancel flights with [script]. */
class MockStatusProvider(private val seed: SeedData = SeedData.default(), private val clock: Clock = Clock.systemUTC()) : StatusProvider {
    override val name = "mock-status"
    private val flights = MockFlightProvider(seed, clock)
    private val scripted = ConcurrentHashMap<String, (FlightStatus) -> FlightStatus>()

    fun script(flightNo: String, date: LocalDate, change: (FlightStatus) -> FlightStatus) { scripted["$flightNo|$date"] = change }
    fun moveBy(flightNo: String, date: LocalDate, minutes: Long) = script(flightNo, date) {
        it.copy(state = triplah.core.FlightState.RETIMED, estimatedDepart = it.scheduledDepart.plusMinutes(minutes), estimatedArrive = it.scheduledArrive.plusMinutes(minutes))
    }
    fun cancel(flightNo: String, date: LocalDate) = script(flightNo, date) { it.copy(state = FlightState.CANCELLED) }

    override suspend fun status(flightNo: String, date: LocalDate): FlightStatus {
        val s = seed.schedules.firstOrNull { it.flightNo == flightNo } ?: throw ProviderUnavailable("unknown flight $flightNo")
        val seg = flights.segment(s, date)
        val base = FlightStatus(flightNo, date, FlightState.SCHEDULED, seg.depart, seg.arrive, null, null, name, clock.instant().toString())
        return scripted["$flightNo|$date"]?.invoke(base) ?: base
    }

    override suspend fun history(flightNo: String): FlightHistory? {
        val h = seed.history.firstOrNull { it.flightNo == flightNo } ?: return null
        val s = seed.schedules.first { it.flightNo == flightNo }
        val sameRoute = seed.schedules.filter { it.from == s.from && it.to == s.to }
        val perDay = sameRoute.sumOf { it.days.size } / 7.0
        val last = sameRoute.maxBy { it.departLocal }.flightNo == flightNo
        return FlightHistory(flightNo, h.days, h.operated, h.cancelled, h.delayedOver60, perDay.roundToInt().coerceAtLeast(1), last, s.carrierType)
    }
}

/** Distance-based estimate: walk under 1 km, otherwise MRT/taxi speed plus 10 minutes of waiting and walking. */
class MockTransitProvider : TransitProvider {
    override val name = "mock-transit"
    override suspend fun minutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, city: String): Int {
        val km = Geo.km(fromLat, fromLon, toLat, toLon)
        return when {
            km < 1.0 -> (km / 4.8 * 60).roundToInt().coerceAtLeast(2)
            km < 20 -> (10 + km / 22 * 60).roundToInt()
            else -> (10 + km / 45 * 60).roundToInt()
        }
    }
}
