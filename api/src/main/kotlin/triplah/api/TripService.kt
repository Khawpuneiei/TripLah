@file:UseSerializers(ZdtSerializer::class, LocalDateSerializer::class)

package triplah.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import triplah.core.*
import triplah.providers.CalendarPrice
import triplah.providers.FlightQuery
import triplah.providers.PriceWatch
import triplah.providers.ProviderHub
import triplah.providers.SeedData
import triplah.providers.StayQuery
import triplah.providers.Store
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// ---------- tool inputs and outputs ----------

@Serializable
data class ImportTimetableIn(
    val traveller: String,
    /** "ics" or "json". Guessed from the content when missing. */
    val format: String? = null,
    /** The file content. Either this or [path]. */
    val content: String? = null,
    /** A local .ics or .json file. */
    val path: String? = null,
    val zone: String = "Asia/Singapore",
    /** Title regex -> MUST_ATTEND / CAN_SKIP, applied before the keyword rules. */
    val overrides: Map<String, Attendance> = emptyMap(),
)

@Serializable
data class ImportTimetableOut(
    val traveller: String,
    val events: Int,
    val mustAttend: Int,
    val canSkip: Int,
    val first: ZonedDateTime?,
    val last: ZonedDateTime?,
    /** First few events so the user can confirm the tagging. */
    val sample: List<Event>,
    val note: String = "Untagged events are must-attend. Add [skip] to a title or pass overrides to change one.",
)

@Serializable
data class FreeWindowsIn(
    val travellers: List<String>,
    val from: ZonedDateTime,
    val to: ZonedDateTime,
    val minHours: Long = 6,
    val buffers: Buffers = Buffers(),
)

@Serializable
data class FreeWindowsOut(val travellers: List<String>, val windows: List<FreeWindow>, val notes: List<String>)

@Serializable
data class PriceCalendarIn(
    val origin: String,
    val destination: String,
    val travellers: List<String>,
    val from: LocalDate,
    val to: LocalDate,
    /** Nights away. 0 = same-day return. */
    val nights: Int = 0,
    val passengers: Int? = null,
)

@Serializable
data class CalendarOption(
    val window: FreeWindow,
    val outDate: LocalDate,
    val backDate: LocalDate,
    val outPricePerSeat: Double,
    val backPricePerSeat: Double,
    val estimatedTotal: Double,
    val outFlight: String?,
    val backFlight: String?,
)

@Serializable
data class PriceCalendarOut(
    val options: List<CalendarOption>,
    val note: String = "Calendar prices are cached (up to 7 days old). Run plan_trip on a date to get live, timetable-checked prices.",
    val errors: List<String> = emptyList(),
)

@Serializable
data class SearchFlightsIn(val from: String, val to: String, val date: LocalDate, val passengers: Int = 1, val maxStops: Int = 1, val limit: Int = 15)

@Serializable
data class SearchFlightsOut(val offers: List<FlightOffer>, val errors: List<String>)

@Serializable
data class SearchStaysIn(val city: String, val checkIn: LocalDate, val checkOut: LocalDate, val guests: Int = 1, val venueId: String? = null, val limit: Int = 15)

@Serializable
data class StayResult(val stay: StayOffer, val minutesToVenue: Int?, val providerTextFlagged: Boolean)

@Serializable
data class SearchStaysOut(val stays: List<StayResult>, val errors: List<String>)

@Serializable
data class SplitIn(val items: List<SplitItem>)

@Serializable
data class WatchPriceIn(val from: String, val to: String, val date: LocalDate, val passengers: Int = 1, val targetPrice: Double, val bookBy: LocalDate)

@Serializable
data class WatchPriceOut(val watch: PriceWatch, val currentPrice: Double?, val advice: BuyOrWait, val note: String)

@Serializable
data class CheckFlightIn(
    val flightNo: String,
    val date: LocalDate,
    /** Leg in the user's plan: "outbound" or "inbound". With travellers, the timetable is re-checked. */
    val leg: String? = null,
    val travellers: List<String> = emptyList(),
    /** Planned times, if this flight is in a plan. Defaults to the scheduled times. */
    val plannedDepart: ZonedDateTime? = null,
    val plannedArrive: ZonedDateTime? = null,
    /** Save the flight so the watcher keeps checking it. */
    val watch: Boolean = false,
    val buffers: Buffers = Buffers(),
    /** Inbound alternatives must leave after this, e.g. the event end plus travel to the airport. */
    val earliestDepart: ZonedDateTime? = null,
)

@Serializable
data class CheckFlightOut(val status: FlightStatus, val recheck: RecheckResult?, val alternatives: List<FlightOffer>, val watchId: Long? = null)

@Serializable
data class FlightRiskIn(val flightNo: String)

@Serializable
data class TravelRulesIn(val question: String, val airline: String? = null, val country: String? = null)

/**
 * Wires the providers to the pure planner. Every tool goes through here,
 * so the REST API, the MCP server, the watcher and the evals share one code path.
 */
class TripService(
    val hub: ProviderHub,
    val store: Store = Store.memory(),
    val seed: SeedData = SeedData.default(),
    val rulesUrl: String = System.getenv("TRIPLAH_RULES_URL") ?: "http://127.0.0.1:8008",
) {
    val clock get() = hub.clock
    val airports get() = seed.airportByCode.keys

    private fun zoneOf(code: String): ZoneId = ZoneId.of(seed.airportByCode.getValue(code).tz)
    private fun now(): ZonedDateTime = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of("Asia/Singapore"))

    // ---- import_timetable ----
    fun importTimetable(i: ImportTimetableIn): ImportTimetableOut {
        val id = Validate.traveller(i.traveller)
        val text = i.content ?: i.path?.let { p ->
            val f = File(p)
            if (f.extension.lowercase() !in setOf("ics", "json")) throw ValidationException("only .ics or .json files can be imported")
            if (!f.isFile) throw ValidationException("file not found: $p")
            if (f.length() > 2_000_000) throw ValidationException("timetable file is over 2 MB")
            f.readText()
        } ?: throw ValidationException("give content or path")
        val format = i.format?.lowercase() ?: if (text.trimStart().startsWith("{")) "json" else "ics"
        val rules = AttendanceRules(i.overrides)
        val tt = when (format) {
            "ics" -> IcsParser.parse(id, text, i.zone, rules)
            "json" -> JsonTimetableParser.parse(id, text, rules)
            else -> throw ValidationException("format must be ics or json")
        }
        // Only titles, times and tags are kept. Descriptions, attendees and organisers are dropped on import.
        store.saveTimetable(tt)
        return ImportTimetableOut(id, tt.events.size, tt.events.count { it.attendance == Attendance.MUST_ATTEND },
            tt.events.count { it.attendance == Attendance.CAN_SKIP }, tt.events.firstOrNull()?.start, tt.events.lastOrNull()?.end, tt.events.take(6))
    }

    private fun timetablesFor(ids: List<String>): Pair<List<Timetable>, List<String>> {
        val notes = mutableListOf<String>()
        val tts = ids.map(Validate::traveller).mapNotNull { id -> store.timetable(id) ?: null.also { notes += "No timetable imported for Traveller $id." } }
        return tts to notes
    }

    // ---- find_free_windows ----
    fun freeWindows(i: FreeWindowsIn): FreeWindowsOut {
        if (Duration.between(i.from, i.to).toDays() > 200) throw ValidationException("range is limited to 200 days")
        val (tts, notes) = timetablesFor(i.travellers)
        // Windows are where everyone can be away from campus, including the trip to and from the airport.
        val b = i.buffers
        val raw = Windows.freeWindows(tts, i.from, i.to, i.minHours)
        val pad = (b.campusToAirportMin + b.checkInBeforeDepartureMin).toLong()
        val windows = raw.filter { Duration.between(it.start, it.end).toMinutes() > pad * 2 }
        val allNotes = notes + "Each window still needs ${b.campusToAirportMin} min to the airport and ${b.checkInBeforeDepartureMin} min check-in on each side."
        return FreeWindowsOut(i.travellers, windows, allNotes)
    }

    // ---- price calendar (LOW_COST, flexible dates) ----
    suspend fun priceCalendar(i: PriceCalendarIn): PriceCalendarOut {
        val o = Validate.airport(i.origin, airports); val d = Validate.airport(i.destination, airports)
        val pax = Validate.pax(i.passengers ?: i.travellers.size.coerceAtLeast(1))
        if (i.nights !in 0..7) throw ValidationException("nights must be 0 to 7")
        val (tts, _) = timetablesFor(i.travellers)
        val zone = zoneOf(o)
        val windows = Windows.freeWindows(tts, i.from.atStartOfDay(zone), i.to.plusDays(1).atStartOfDay(zone), minHours = maxOf(10L, 24L * i.nights + 10))
        val (outCal, backCal) = coroutineScope {
            val a = async { hub.calendar(o, d, i.from, i.to) }; val b = async { hub.calendar(d, o, i.from, i.to.plusDays(i.nights.toLong())) }
            a.await() to b.await()
        }
        val back = backCal.items.associateBy { it.date }
        val options = windows.mapNotNull { w ->
            var best: CalendarOption? = null
            var day = w.start.toLocalDate()
            while (!day.plusDays(i.nights.toLong()).isAfter(w.end.toLocalDate())) {
                val out: CalendarPrice? = outCal.items.firstOrNull { it.date == day }
                val ret = back[day.plusDays(i.nights.toLong())]
                if (out != null && ret != null) {
                    val total = money((out.pricePerSeat + ret.pricePerSeat) * pax)
                    if (best == null || total < best.estimatedTotal)
                        best = CalendarOption(w, day, day.plusDays(i.nights.toLong()), out.pricePerSeat, ret.pricePerSeat, total, out.flightNo, ret.flightNo)
                }
                day = day.plusDays(1)
            }
            best
        }.sortedBy { it.estimatedTotal }.take(10)
        return PriceCalendarOut(options, errors = outCal.errors + backCal.errors)
    }

    // ---- search_flights / search_stays ----
    suspend fun searchFlights(i: SearchFlightsIn): SearchFlightsOut {
        val q = FlightQuery(Validate.airport(i.from, airports), Validate.airport(i.to, airports), i.date, Validate.pax(i.passengers), i.maxStops.coerceIn(0, 1))
        Validate.date(i.date, now().toLocalDate())
        val r = hub.searchFlights(q)
        return SearchFlightsOut(r.items.sortedBy { it.price(false) }.take(i.limit.coerceIn(1, 50)), r.errors)
    }

    suspend fun searchStays(i: SearchStaysIn): SearchStaysOut {
        val city = seed.airportByCode[i.city.uppercase()]?.city ?: seed.airports.firstOrNull { it.city.equals(i.city, true) }?.city
            ?: throw ValidationException("unknown city '${i.city}'")
        if (!i.checkOut.isAfter(i.checkIn)) throw ValidationException("check-out must be after check-in")
        Validate.pax(i.guests)
        val venue = i.venueId?.let { seed.venueById[it] ?: throw ValidationException("unknown venue '$it'") }
        val r = hub.searchStays(StayQuery(city, i.checkIn, i.checkOut, i.guests, venue?.lat, venue?.lon))
        val out = r.items.map { s ->
            val m = venue?.let { hub.minutes(s.lat, s.lon, it.lat, it.lon, city) }
            StayResult(s.copy(description = ProviderText.sanitize(s.description)), m, ProviderText.looksLikeInjection(s.description))
        }.sortedWith(compareBy({ it.minutesToVenue ?: 999 }, { it.stay.rooms.minOf { r -> r.pricePerNight } }))
        return SearchStaysOut(out.take(i.limit.coerceIn(1, 50)), r.errors)
    }

    // ---- plan_trip ----
    suspend fun planTrip(raw: TripRequest): PlanResult {
        val now = raw.now ?: now()
        val r = Validate.request(raw, airports, now)
        val ids = (r.travellers + listOfNotNull(r.hostTraveller)).distinct()
        val (tts, missing) = timetablesFor(ids)
        val timetables = tts.associateBy { it.travellerId }
        val errors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val input = when (r.painPoint) {
            PainPoint.EVENT_TRIP -> gatherEventTrip(r, timetables, now, errors)
            PainPoint.FAMILY_VISIT -> gatherFamilyVisit(r, timetables, now, errors)
        }
        val result = Planner.plan(input)
        val confirmed = confirmFresh(result)
        val extra = missing.filter { m -> confirmed.notes.none { it.startsWith(m.take(25)) } } + errors.distinct().map { "Provider problem: $it" }
        return confirmed.copy(notes = confirmed.notes + extra)
    }

    private suspend fun flights(from: String, to: String, dates: List<LocalDate>, pax: Int, maxStops: Int, errors: MutableList<String>): List<FlightOffer> = coroutineScope {
        dates.distinct().map { d -> async { hub.searchFlights(FlightQuery(from, to, d, pax, maxStops)) } }.awaitAll()
            .flatMap { errors += it.errors; it.items }
    }

    private suspend fun gatherEventTrip(r: TripRequest, tts: Map<String, Timetable>, now: ZonedDateTime, errors: MutableList<String>): PlannerInput {
        val ev = r.event!!
        val pax = r.passengers ?: r.travellers.size.coerceAtLeast(1)
        val maxStops = ModeRules.maxStops(r.mode)
        val venue = ev.venueId?.let { seed.venueById[it] ?: throw ValidationException("unknown venue '$it'") }
        if (venue != null && venue.airport != r.destination) throw ValidationException("venue ${venue.id} is in ${venue.city}, not near ${r.destination}")
        val dest = seed.airportByCode.getValue(r.destination)
        val evStart = ev.start.withZoneSameInstant(zoneOf(r.destination)).toLocalDate()
        val evEnd = ev.end.withZoneSameInstant(zoneOf(r.destination)).toLocalDate()
        val (outs, ins) = coroutineScope {
            val a = async { flights(r.origin, r.destination, listOf(evStart.minusDays(1), evStart), pax, maxStops, errors) }
            val b = async { flights(r.destination, r.origin, listOf(evEnd, evEnd.plusDays(1)), pax, maxStops, errors) }
            a.await() to b.await()
        }
        val airportToVenue = venue?.let { hub.minutes(dest.lat, dest.lon, it.lat, it.lon, dest.city) } ?: 40
        val stays = mutableMapOf<StayKey, List<StayOffer>>()
        val minutes = mutableMapOf<String, Int>()
        if (!r.organiserCoversStay) {
            val keys = outs.flatMap { o -> ins.map { i -> StayKey(o.arrive.toLocalDate(), i.depart.toLocalDate()) } }.filter { it.nights in 1..8 }.distinct()
            coroutineScope {
                keys.map { k -> async { k to hub.searchStays(StayQuery(dest.city, k.checkIn, k.checkOut, pax, venue?.lat, venue?.lon)) } }.awaitAll()
            }.forEach { (k, res) -> stays[k] = res.items; errors += res.errors }
            val all = stays.values.flatten().distinctBy { it.id }
            coroutineScope {
                all.map { s -> async { s.id to (venue?.let { v -> hub.minutes(s.lat, s.lon, v.lat, v.lon, dest.city) }) } }.awaitAll()
            }.forEach { (id, m) -> if (m != null) minutes[id] = m }
        }
        return PlannerInput(r, tts, mapOf("outbound" to outs, "inbound" to ins), stays, minutes, airportToVenue, now)
    }

    private suspend fun gatherFamilyVisit(r: TripRequest, tts: Map<String, Timetable>, now: ZonedDateTime, errors: MutableList<String>): PlannerInput {
        val family = r.familySize!!
        val together = family + (if (r.hostTraveller != null) 1 else 0)
        val home = r.arriveOn!!.plusDays(r.nights!!.toLong())
        val dest = seed.airportByCode.getValue(r.destination)
        val stops = ModeRules.maxStops(r.mode)
        val (l1, l2, l3) = coroutineScope {
            val a = async { flights(r.origin, r.destination, listOf(r.arriveOn!!), family, stops, errors) }
            val b = async { flights(r.destination, r.origin, listOf(home), together, stops, errors) }
            val c = async { r.hostReturnOn?.let { flights(r.origin, r.destination, listOf(it), 1, 1, errors) } ?: emptyList() }
            Triple(a.await(), b.await(), c.await())
        }
        val keys = l1.map { StayKey(it.arrive.toLocalDate(), home) }.filter { it.nights > 0 }.distinct()
        val stays = coroutineScope { keys.map { k -> async { k to hub.searchStays(StayQuery(dest.city, k.checkIn, k.checkOut, family)) } }.awaitAll() }
            .associate { (k, res) -> errors += res.errors; k to res.items }
        return PlannerInput(r, tts, mapOf("leg1" to l1, "leg2" to l2, "leg3" to l3), stays, emptyMap(), 40, now)
    }

    /** Offers older than the cache TTL are searched again before they are shown as final. */
    private suspend fun confirmFresh(result: PlanResult): PlanResult {
        val stale = result.plans.flatMap { it.flights }.filter { Duration.between(it.fetchedAt, clock.instant()) > hub.offerTtl }
        if (stale.isEmpty()) return result
        val notes = stale.mapNotNull { f ->
            val fresh = hub.searchFlights(FlightQuery(f.from, f.to, f.depart.toLocalDate(), f.pax, f.stops), fresh = true).items.firstOrNull { it.id == f.offerId }
            when {
                fresh == null -> "${f.flightNos} could not be re-checked and may no longer be available."
                money(fresh.price(f.checkedBag)) != f.price -> "${f.flightNos} price changed to ${Explain.fmt(fresh.price(f.checkedBag))} on re-check."
                else -> null
            }
        }
        return result.copy(notes = result.notes + notes)
    }

    // ---- split_budget ----
    fun splitBudget(i: SplitIn): SplitResult {
        i.items.flatMap { it.sharedBy }.forEach { Validate.noIdNumbers(it, "name") }
        return Split.split(i.items)
    }

    // ---- watch_price ----
    suspend fun watchPrice(i: WatchPriceIn): WatchPriceOut {
        val from = Validate.airport(i.from, airports); val to = Validate.airport(i.to, airports)
        Validate.pax(i.passengers); Validate.date(i.date, now().toLocalDate())
        if (i.targetPrice <= 0) throw ValidationException("target price must be positive")
        if (i.bookBy.isAfter(i.date)) throw ValidationException("book-by date must be on or before the flight date")
        val res = hub.searchFlights(FlightQuery(from, to, i.date, i.passengers, 1))
        val cheapest = res.items.minOfOrNull { it.price(false) }
        if (cheapest != null) store.recordPrice(from, to, i.date, money(cheapest / i.passengers), clock.instant())
        val id = store.addPriceWatch(PriceWatch(from = from, to = to, date = i.date, pax = i.passengers, targetPrice = i.targetPrice, bookBy = i.bookBy))
        val advice = Pricing.buyOrWait(cheapest ?: 0.0, store.routePrices(from, to).map { it * i.passengers }, now().toLocalDate(), i.date, i.bookBy)
        val w = store.priceWatches().first { it.id == id }
        return WatchPriceOut(w, cheapest, advice,
            "The watcher checks once or twice a day and alerts when the total drops below ${Explain.fmt(i.targetPrice)}. On ${i.bookBy} it alerts you to book whatever the price.")
    }

    // ---- check_flight ----
    suspend fun checkFlight(i: CheckFlightIn): CheckFlightOut {
        val flightNo = i.flightNo.uppercase().replace(" ", "")
        if (!Regex("^[A-Z0-9]{2}\\d{1,4}$").matches(flightNo)) throw ValidationException("'${i.flightNo}' is not a flight number")
        val status = hub.flightStatus(flightNo, i.date).getOrElse { throw it }
        status.let { s -> val delay = Duration.between(s.scheduledDepart, s.estimatedDepart ?: s.scheduledDepart).toMinutes(); store.recordFlight(flightNo, i.date, s.state.name, delay) }
        val (tts, _) = timetablesFor(i.travellers)
        val watched = WatchedFlight(flightNo, i.date, flightFrom(status), flightTo(status), i.plannedDepart ?: status.scheduledDepart, i.plannedArrive ?: status.scheduledArrive, i.leg ?: "inbound", i.travellers)
        val recheck = if (i.leg != null || i.travellers.isNotEmpty()) Recheck.evaluate(watched, status, tts, i.buffers) else null
        val alternatives = if (recheck?.changed == true && (recheck.cancelled || recheck.clashes.isNotEmpty())) {
            alternativesFor(watched, tts, i.buffers, i.earliestDepart)
        } else emptyList()
        val watchId = if (i.watch) store.addFlightWatch(watched, Recheck.nextCheck(now(), watched.depart) ?: now()) else null
        return CheckFlightOut(status, recheck, alternatives, watchId)
    }

    private fun flightFrom(s: FlightStatus) = seed.schedules.firstOrNull { it.flightNo == s.flightNo }?.from ?: "?"
    private fun flightTo(s: FlightStatus) = seed.schedules.firstOrNull { it.flightNo == s.flightNo }?.to ?: "?"

    /**
     * Same route, same or next day, that keeps every must-attend class.
     * Inbound: leaves no earlier than [earliestDepart] (default: 3 h before the planned flight) and lands before any class it would clash with.
     * Outbound: lands no later than 3 h after the planned flight.
     */
    suspend fun alternativesFor(w: WatchedFlight, tts: List<Timetable>, b: Buffers, earliestDepart: ZonedDateTime? = null): List<FlightOffer> {
        if (w.from == "?" || w.to == "?") return emptyList()
        val pax = w.travellers.size.coerceAtLeast(1)
        val notBefore = maxOf((earliestDepart ?: w.depart.minusHours(3)).toInstant(), clock.instant())
        val offers = listOf(w.date, w.date.plusDays(1)).flatMap { hub.searchFlights(FlightQuery(w.from, w.to, it, pax, 1)).items }
        return offers.filter { it.flightNos != w.flightNo }.filter { o ->
            if (w.leg == "inbound") {
                val back = o.arrive.plusMinutes((b.immigrationOnArrivalMin + b.campusToAirportMin).toLong())
                !o.depart.toInstant().isBefore(notBefore) && Windows.check(tts, minOf(w.arrive, o.arrive), back).ok
            } else {
                val leave = o.depart.minusMinutes((b.checkInBeforeDepartureMin + b.campusToAirportMin).toLong())
                !o.arrive.isAfter(w.arrive.plusHours(3)) && !o.depart.toInstant().isBefore(clock.instant()) && Windows.check(tts, leave, maxOf(w.depart, o.depart)).ok
            }
        }.sortedWith(compareBy({ it.arrive.toInstant() }, { it.price(false) })).take(3)
    }

    // ---- flight_risk ----
    suspend fun flightRisk(i: FlightRiskIn): RiskSignal {
        val no = i.flightNo.uppercase().replace(" ", "")
        val own = store.flightRecords(no)
        val sched = seed.schedules.firstOrNull { it.flightNo == no }
        val h = hub.flightHistory(no).getOrElse { throw it } ?: run {
            // No provider history (e.g. AeroDataBox free tier): use only what the watcher has recorded.
            if (own.isEmpty()) throw ValidationException("no history for $no yet. Watch it with check_flight {watch:true} to start collecting.")
            FlightHistory(no, 90, 0, 0, 0, 2, false,
                sched?.carrierType ?: if (no.take(2) in triplah.providers.real.LOW_COST_AIRLINES) CarrierType.LOW_COST else CarrierType.FULL_SERVICE)
        }
        val merged = h.copy(
            operated = h.operated + own.count { it.second != "CANCELLED" },
            cancelled = h.cancelled + own.count { it.second == "CANCELLED" },
            delayedOver60 = h.delayedOver60 + own.count { it.third > 60 },
        )
        return Risk.signal(merged)
    }

    // ---- ask_travel_rules (stretch: Python RAG service) ----
    suspend fun askTravelRules(i: TravelRulesIn): kotlinx.serialization.json.JsonElement {
        if (i.question.length > 500) throw ValidationException("question is too long")
        val body = TripJson.encodeToString(TravelRulesIn.serializer(), i)
        val res = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val c = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                val req = java.net.http.HttpRequest.newBuilder(java.net.URI("$rulesUrl/ask")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
                c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: Exception) {
            throw triplah.providers.ProviderUnavailable("the travel rules service is not running at $rulesUrl (start rules-rag: uvicorn app:app --port 8008)")
        }
        if (res.statusCode() != 200) throw triplah.providers.ProviderUnavailable("travel rules service returned HTTP ${res.statusCode()}")
        return TripJson.parseToJsonElement(res.body())
    }
}
