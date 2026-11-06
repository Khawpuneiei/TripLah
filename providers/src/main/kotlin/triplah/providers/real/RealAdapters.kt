package triplah.providers.real

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.withLock
import triplah.core.CarrierType
import triplah.core.FlightHistory
import triplah.core.FlightOffer
import triplah.core.FlightState
import triplah.core.FlightStatus
import triplah.core.RoomKind
import triplah.core.RoomOption
import triplah.core.Segment
import triplah.core.StayOffer
import triplah.core.StayType
import triplah.core.TripJson
import triplah.core.money
import triplah.providers.CalendarPrice
import triplah.providers.FlightProvider
import triplah.providers.FlightQuery
import triplah.providers.HttpGateway
import triplah.providers.ProviderUnavailable
import triplah.providers.StatusProvider
import triplah.providers.StayProvider
import triplah.providers.StayQuery
import triplah.providers.TransitProvider
import java.net.URLEncoder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private operator fun JsonElement?.get(k: String): JsonElement? = (this as? JsonObject)?.get(k)

/** Airports this build knows, for time zones in provider responses that give only local times. */
val AIRPORT_ZONES = mapOf("SIN" to "Asia/Singapore", "BKK" to "Asia/Bangkok", "DMK" to "Asia/Bangkok", "KUL" to "Asia/Kuala_Lumpur",
    "HKG" to "Asia/Hong_Kong", "TPE" to "Asia/Taipei", "MNL" to "Asia/Manila")

val LOW_COST_AIRLINES = setOf("TR", "FD", "AK", "3K", "UO", "IT", "5J", "VZ", "SL", "DD", "QZ", "Z2", "JQ", "OD")

/**
 * SerpApi Google Flights. One-way searches. Prices are totals for all adults in the requested currency.
 * Google's price does not include checked bags, so priceWithBag equals the base price and the source says so.
 */
class SerpApiFlights(private val gw: HttpGateway, private val key: String, private val clock: Clock = Clock.systemUTC()) : FlightProvider {
    override val name = "serpapi"
    private val t = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override suspend fun search(q: FlightQuery): List<FlightOffer> {
        val stops = if (q.maxStops == 0) 1 else 2 // SerpApi: 1 = nonstop only, 2 = one stop or fewer
        val url = "https://serpapi.com/search.json?engine=google_flights&type=2&currency=SGD&hl=en" +
            "&departure_id=${enc(q.from)}&arrival_id=${enc(q.to)}&outbound_date=${q.date}&adults=${q.pax}&stops=$stops&api_key=${enc(key)}"
        return parse(gw.get(name, url), q.pax)
    }

    fun parse(body: String, pax: Int): List<FlightOffer> {
        val root = TripJson.parseToJsonElement(body)
        root["error"].str()?.let { throw ProviderUnavailable("serpapi: $it") }
        val groups = listOfNotNull(root["best_flights"] as? JsonArray, root["other_flights"] as? JsonArray).flatten()
        return groups.mapNotNull { g ->
            val price = g["price"].num() ?: return@mapNotNull null
            val legs = (g["flights"] as? JsonArray)?.map { f ->
                val dep = f["departure_airport"]; val arr = f["arrival_airport"]
                val from = dep["id"].str() ?: return@mapNotNull null; val to = arr["id"].str() ?: return@mapNotNull null
                val no = f["flight_number"].str()?.replace(" ", "") ?: return@mapNotNull null
                val zf = ZoneId.of(AIRPORT_ZONES[from] ?: return@mapNotNull null); val zt = ZoneId.of(AIRPORT_ZONES[to] ?: return@mapNotNull null)
                Segment(no, no.take(2), if (no.take(2) in LOW_COST_AIRLINES) CarrierType.LOW_COST else CarrierType.FULL_SERVICE, from, to,
                    LocalDateTime.parse(dep["time"].str(), t).atZone(zf), LocalDateTime.parse(arr["time"].str(), t).atZone(zt))
            } ?: return@mapNotNull null
            if (legs.isEmpty()) return@mapNotNull null
            val ids = legs.joinToString("-") { it.flightNo }
            FlightOffer(
                id = "serpapi:$ids:${legs.first().depart.toLocalDate()}:$pax", segments = legs, pax = pax,
                priceCarryOn = money(price), priceWithBag = money(price), seatsLeft = null, singleTicket = true,
                bookingLink = "https://www.google.com/travel/flights?q=" + enc("Flights from ${legs.first().from} to ${legs.last().to} on ${legs.first().depart.toLocalDate()}"),
                source = "serpapi:google_flights (checked-bag fee not included)", fetchedAt = clock.instant(),
            )
        }
    }
}

/** Travelpayouts Aviasales Data API: cached cheapest prices per date (up to 7 days old). Calendar only. */
class TravelpayoutsCalendar(private val gw: HttpGateway, private val token: String, private val clock: Clock = Clock.systemUTC()) : FlightProvider {
    override val name = "travelpayouts"
    override suspend fun search(q: FlightQuery): List<FlightOffer> = emptyList()

    override suspend fun calendar(from: String, to: String, start: LocalDate, end: LocalDate): List<CalendarPrice> {
        val months = generateSequence(start.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }.toList()
        return months.flatMap { m ->
            val url = "https://api.travelpayouts.com/aviasales/v3/prices_for_dates?origin=$from&destination=$to&departure_at=${m.toString().take(7)}" +
                "&one_way=true&currency=sgd&sorting=price&limit=1000&token=${enc(token)}"
            parse(gw.get(name, url), from, to)
        }.filter { it.date in start..end }.groupBy { it.date }.map { (_, v) -> v.minBy { it.pricePerSeat } }
    }

    fun parse(body: String, from: String, to: String): List<CalendarPrice> {
        val root = TripJson.parseToJsonElement(body)
        if ((root["success"] as? JsonPrimitive)?.content == "false") throw ProviderUnavailable("travelpayouts: ${root["error"].str()}")
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { d ->
            val dep = d["departure_at"].str() ?: return@mapNotNull null
            CalendarPrice(from, to, OffsetDateTime.parse(dep).toLocalDate(), money(d["price"].num() ?: return@mapNotNull null),
                listOfNotNull(d["airline"].str(), d["flight_number"].let { (it as? JsonPrimitive)?.content }).joinToString(""), name, clock.instant())
        }
    }
}

/**
 * LiteAPI v3 (sandbox key works). Hotel list + rates. LiteAPI gives no room size, lift or MRT data,
 * so those stay null and the planner shows them as "unknown". Capsule/hostel type is guessed from the name.
 */
class LiteApiStays(private val gw: HttpGateway, private val key: String, private val clock: Clock = Clock.systemUTC()) : StayProvider {
    override val name = "liteapi"
    private val countries = mapOf("Singapore" to "SG", "Bangkok" to "TH", "Kuala Lumpur" to "MY", "Hong Kong" to "HK", "Taipei" to "TW", "Manila" to "PH")

    /** The sandbox rate-limits bursts: one search at a time. */
    private val oneAtATime = kotlinx.coroutines.sync.Mutex()
    private val hotelLists = java.util.concurrent.ConcurrentHashMap<String, Map<String, Hotel>>()

    override suspend fun search(q: StayQuery): List<StayOffer> = oneAtATime.withLock { searchNow(q) }

    private suspend fun searchNow(q: StayQuery): List<StayOffer> {
        val cc = countries[q.city] ?: throw ProviderUnavailable("liteapi: no country for ${q.city}")
        val h = mapOf("X-API-Key" to key, "accept" to "application/json")
        val geo = if (q.nearLat != null && q.nearLon != null) "&latitude=${q.nearLat}&longitude=${q.nearLon}&radius=3000" else "&cityName=${enc(q.city)}"
        val base = "https://api.liteapi.travel/v3.0/data/hotels?countryCode=$cc$geo&limit=40"
        // Two lists: the nearest hotels, and the nearest hostels/capsules (otherwise ordinary hotels crowd them out).
        // The lists do not depend on dates, so they are cached per place: each date pair then costs one rates call.
        val hotels = hotelLists.getOrPut(base) {
            parseHotels(gw.get(name, base, h)) + parseHotels(gw.get(name, "$base&hotelTypeIds=${BUDGET_TYPES.joinToString(",")}", h))
        }
        if (hotels.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("hotelIds", buildJsonArray { hotels.keys.forEach { add(JsonPrimitive(it)) } })
            put("checkin", q.checkIn.toString()); put("checkout", q.checkOut.toString()); put("currency", "SGD"); put("guestNationality", "SG")
            put("occupancies", buildJsonArray { add(buildJsonObject { put("adults", q.guests) }) })
        }
        return parseRates(gw.post(name, "https://api.liteapi.travel/v3.0/hotels/rates", body.toString(), h), hotels, q)
    }

    data class Hotel(val id: String, val name: String, val lat: Double, val lon: Double, val description: String,
                     val typeId: Int? = null, val facilities: Set<Int> = emptySet()) {
        /** LiteAPI hotel types (GET /data/hotelTypes): 225 capsule hotels; 203 hostels, 264 hostel/backpacker; 201 apartments, 219 aparthotels. */
        val type: StayType get() {
            val n = name.lowercase()
            return when {
                typeId == 225 || "capsule" in n || Regex("\\bpods?\\b").containsMatchIn(n) -> StayType.CAPSULE
                typeId == 203 || typeId == 264 || "hostel" in n || "backpacker" in n -> StayType.HOSTEL
                typeId == 201 || typeId == 219 -> StayType.SERVICED_APARTMENT
                else -> StayType.HOTEL
            }
        }
        // LiteAPI facilities (GET /data/facilities). A facility that is not listed is unknown, not false.
        val lateCheckIn: Boolean? get() = if (8 in facilities) true else null           // 24-hour front desk
        val lockers: Boolean? get() = if (142 in facilities) true else null             // Lockers
        val hasLift: Boolean? get() = when {
            48 in facilities || 491 in facilities -> true                               // Lift / Elevator, Elevator
            972 in facilities -> false                                                  // No elevator
            else -> null
        }
    }

    companion object {
        val BUDGET_TYPES = listOf(203, 225, 264)

        private val shared = Regex("""\bbeds? in\b|\bdorm|\bmixed\b|\bfemale\b|\bmale\b|\bshared\b|\bcommon\b|\bdecker\b""")
        private val podWords = Regex("""\bcapsules?\b|\bpods?\b""")
        private val privateWords = Regex("""\bprivate\b|\bdouble\b|\btwin\b|\bking\b|\bqueen\b|\bsingle room\b|\bdeluxe\b|\bstudio\b|\bsuite\b|\bresidence\b|\bbedroom\b""")

        /**
         * Room kind from a LiteAPI rate name. Order matters: family rooms first ("Family Room with Bunk Bed" is private),
         * then explicit shared beds and pods ("1 Bed in 12 Beds Mixed Room, 1 Twin Bunk Bed" is a dorm bed),
         * then private-room words ("Budget Double Room", "Private Bunk Bed"), then the property type as a last resort.
         */
        fun roomKind(name: String, type: StayType, occupancy: Int): RoomKind {
            val n = name.lowercase()
            return when {
                "family" in n -> RoomKind.FAMILY_ROOM
                podWords.containsMatchIn(n) && !privateWords.containsMatchIn(n.replace("single capsule", "")) -> RoomKind.POD
                shared.containsMatchIn(n) -> if (podWords.containsMatchIn(n)) RoomKind.POD else RoomKind.DORM_BED
                "suite" in n -> RoomKind.SUITE
                "twin" in n -> RoomKind.PRIVATE_TWIN
                privateWords.containsMatchIn(n) -> RoomKind.PRIVATE_DOUBLE
                type == StayType.CAPSULE && occupancy <= 1 -> RoomKind.POD
                type == StayType.HOSTEL && occupancy <= 1 && "bunk" in n -> RoomKind.DORM_BED
                else -> RoomKind.PRIVATE_DOUBLE
            }
        }
    }

    fun parseHotels(body: String): Map<String, Hotel> = (TripJson.parseToJsonElement(body)["data"] as? JsonArray).orEmpty().mapNotNull { h ->
        Hotel(h["id"].str() ?: return@mapNotNull null, h["name"].str() ?: "?", h["latitude"].num() ?: return@mapNotNull null,
            h["longitude"].num() ?: return@mapNotNull null, h["hotelDescription"].str()?.replace(Regex("<[^>]+>"), " ")?.take(600) ?: "",
            (h["hotelTypeId"] as? JsonPrimitive)?.intOrNull,
            (h["facilityIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet())
    }.associateBy { it.id }

    fun parseRates(body: String, hotels: Map<String, Hotel>, q: StayQuery): List<StayOffer> {
        val nights = java.time.temporal.ChronoUnit.DAYS.between(q.checkIn, q.checkOut).coerceAtLeast(1)
        return (TripJson.parseToJsonElement(body)["data"] as? JsonArray).orEmpty().mapNotNull { d ->
            val hotel = hotels[d["hotelId"].str()] ?: return@mapNotNull null
            val type = hotel.type
            val rooms = (d["roomTypes"] as? JsonArray).orEmpty().mapNotNull { rt ->
                val rate = (rt["rates"] as? JsonArray)?.firstOrNull() ?: return@mapNotNull null
                val total = (rt["offerRetailRate"]["amount"]).num() ?: (rate["retailRate"]["total"] as? JsonArray)?.firstOrNull()?.get("amount").num() ?: return@mapNotNull null
                val rname = rate["name"].str() ?: "Room"
                val occ = (rate["maxOccupancy"] as? JsonPrimitive)?.intOrNull ?: q.guests
                val kind = roomKind(rname, type, occ)
                RoomOption(rt["offerId"].str() ?: rname, rname, kind, if (kind == RoomKind.POD || kind == RoomKind.DORM_BED) 1 else occ, null, money(total / nights))
            }
            if (rooms.isEmpty()) return@mapNotNull null
            StayOffer(hotel.id, hotel.name, q.city, type, hotel.lat, hotel.lon, rooms, lateCheckIn = hotel.lateCheckIn, lockers = hotel.lockers, hasLift = hotel.hasLift, mrtWalkMin = null,
                description = hotel.description, bookingLink = "https://www.liteapi.travel/", source = name, fetchedAt = clock.instant())
        }
    }
}

/** AeroDataBox (RapidAPI). Status by flight number and date. No history on the free tier: TripLah builds its own. */
class AeroDataBoxStatus(private val gw: HttpGateway, private val key: String, private val clock: Clock = Clock.systemUTC()) : StatusProvider {
    override val name = "aerodatabox"
    override suspend fun status(flightNo: String, date: LocalDate): FlightStatus {
        val body = gw.get(name, "https://aerodatabox.p.rapidapi.com/flights/number/${enc(flightNo)}/$date",
            mapOf("X-RapidAPI-Key" to key, "X-RapidAPI-Host" to "aerodatabox.p.rapidapi.com"))
        return parse(body, flightNo, date)
    }

    fun parse(body: String, flightNo: String, date: LocalDate): FlightStatus {
        val f = (TripJson.parseToJsonElement(body) as? JsonArray)?.firstOrNull() ?: throw ProviderUnavailable("aerodatabox: no data for $flightNo on $date")
        fun time(side: String, which: String): ZonedDateTime? {
            val node = f[side]
            val s = node[which]["local"].str() ?: node["${which}Local"].str() ?: return null
            return OffsetDateTime.parse(s.replace(" ", "T").let { if (Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(it)) it else "${it}Z" }).toZonedDateTime()
        }
        val sd = time("departure", "scheduledTime") ?: throw ProviderUnavailable("aerodatabox: no scheduled time")
        val sa = time("arrival", "scheduledTime") ?: sd
        val state = when (f["status"].str()?.lowercase()) {
            "canceled", "cancelled", "canceleduncertain" -> FlightState.CANCELLED
            "delayed" -> FlightState.DELAYED
            "departed", "enroute" -> FlightState.DEPARTED
            "arrived" -> FlightState.LANDED
            "expected", "checkin", "boarding", "gateclosed", "approaching" -> FlightState.SCHEDULED
            else -> FlightState.UNKNOWN
        }
        val ed = time("departure", "revisedTime"); val ea = time("arrival", "revisedTime")
        return FlightStatus(flightNo, date, if (state == FlightState.SCHEDULED && ed != null && ed != sd) FlightState.RETIMED else state,
            sd, sa, ed, ea, name, clock.instant().toString())
    }

    override suspend fun history(flightNo: String): FlightHistory? = null
}

/** OneMap public-transport routing. Singapore only; other cities fall back to the estimate. */
class OneMapTransit(private val gw: HttpGateway, private val token: String) : TransitProvider {
    override val name = "onemap"
    override suspend fun minutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, city: String): Int {
        if (city != "Singapore") throw ProviderUnavailable("onemap covers Singapore only")
        val now = java.time.ZonedDateTime.now(ZoneId.of("Asia/Singapore"))
        val url = "https://www.onemap.gov.sg/api/public/routingsvc/route?start=$fromLat,$fromLon&end=$toLat,$toLon&routeType=pt&mode=TRANSIT" +
            "&date=${now.format(DateTimeFormatter.ofPattern("MM-dd-yyyy"))}&time=09:00:00&maxWalkDistance=1000&numItineraries=1"
        return parse(gw.get(name, url, mapOf("Authorization" to token)))
    }

    fun parse(body: String): Int {
        val it0 = (TripJson.parseToJsonElement(body)["plan"]["itineraries"] as? JsonArray)?.firstOrNull() ?: throw ProviderUnavailable("onemap: no route")
        val seconds = it0["duration"].num() ?: throw ProviderUnavailable("onemap: no duration")
        return (seconds / 60).toInt().coerceAtLeast(1)
    }
}
