package triplah.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Hard rules per mode. Each check returns a rejection reason, or null when the candidate passes. */
object ModeRules {
    const val CONVENIENT_MAX_MIN_TO_VENUE = 15
    const val DEFAULT_MAX_MIN_TO_VENUE = 45
    const val FAMILY_MIN_ROOM_M2 = 25.0
    const val FAMILY_MAX_MRT_WALK_MIN = 10
    const val MAX_ROOMS = 3
    /** SGD per minute of travel to the venue, used to rank LOW_COST_CONVENIENT. */
    const val VALUE_OF_MINUTE = 0.30
    /** Ranking only: each unknown field on a stay counts as this many SGD, so known-good stays come first. */
    const val UNKNOWN_RANK_PENALTY = 100_000.0

    fun checkedBag(mode: Mode) = mode == Mode.FAMILY

    fun maxStops(mode: Mode) = when (mode) {
        Mode.FAMILY -> 0
        else -> 1
    }

    fun rejectFlight(mode: Mode, offer: FlightOffer, pax: Int): String? = when {
        offer.pax != pax -> "priced for ${offer.pax} seats, need $pax"
        offer.seatsLeft != null && offer.seatsLeft < pax -> "fewer than $pax seats left on one booking"
        offer.stops > maxStops(mode) -> if (mode == Mode.FAMILY) "FAMILY: no stops" else "more than one stop"
        mode == Mode.FAMILY && !offer.singleTicket -> "FAMILY: self-transfer on separate tickets refused"
        mode == Mode.FAMILY && offer.isRedEye -> "FAMILY: no red-eye"
        else -> null
    }

    fun isLateNight(t: java.time.ZonedDateTime) = t.hour >= 22 || t.hour < 6

    /**
     * The best way to sleep [pax] people at [stay] for [nights] under [mode], or a rejection reason.
     * For LOW_COST_CONVENIENT with 2+ travellers, both shared beds and one private room are returned so the caller can compare.
     */
    fun stayOptions(mode: Mode, stay: StayOffer, pax: Int, nights: Int, minutesToVenue: Int?): Pair<List<StayChoice>, String?> {
        val maxMin = if (mode == Mode.LOW_COST_CONVENIENT) CONVENIENT_MAX_MIN_TO_VENUE else DEFAULT_MAX_MIN_TO_VENUE
        if (mode != Mode.FAMILY && minutesToVenue != null && minutesToVenue > maxMin) return emptyList<StayChoice>() to "more than $maxMin min to the venue"
        val stayReject = when (mode) {
            Mode.LOW_COST_CONVENIENT -> when {
                minutesToVenue == null -> "minutes to venue unknown"
                // Only a stay known to lack late check-in is rejected; an unlisted one is shown as unknown and ranked lower.
                stay.lateCheckIn == false -> "CONVENIENT: no late check-in"
                else -> null
            }
            Mode.FAMILY -> when {
                stay.hasLift == false -> "FAMILY: no lift"
                stay.mrtWalkMin != null && stay.mrtWalkMin > FAMILY_MAX_MRT_WALK_MIN -> "FAMILY: MRT more than $FAMILY_MAX_MRT_WALK_MIN min walk"
                else -> null
            }
            else -> null
        }
        if (stayReject != null) return emptyList<StayChoice>() to stayReject

        val rooms = stay.rooms.filter { room ->
            when (mode) {
                Mode.LOW_COST_CONVENIENT -> when {
                    !room.isPrivate -> stay.type in setOf(StayType.CAPSULE, StayType.HOSTEL) && stay.lockers != false
                    else -> pax >= 2 && room.kind in setOf(RoomKind.PRIVATE_TWIN, RoomKind.PRIVATE_DOUBLE) && room.sleeps >= pax
                }
                Mode.FAMILY -> room.isPrivate && (room.kind == RoomKind.FAMILY_ROOM || room.kind == RoomKind.SUITE ||
                    room.sizeM2 == null || room.sizeM2 >= FAMILY_MIN_ROOM_M2)
                else -> true
            }
        }
        if (rooms.isEmpty()) return emptyList<StayChoice>() to when (mode) {
            Mode.LOW_COST_CONVENIENT -> "CONVENIENT: no capsule/hostel bed with lockers"
            Mode.FAMILY -> "FAMILY: no private room of $FAMILY_MIN_ROOM_M2 m² or family room"
            else -> "no room"
        }
        val choices = rooms.mapNotNull { room ->
            val count = if (room.isPrivate) ceil(pax / room.sleeps.toDouble()).toInt() else pax
            if (room.isPrivate && count > MAX_ROOMS) return@mapNotNull null
            if (room.roomsLeft != null && room.roomsLeft < count) return@mapNotNull null
            val unknown = buildList {
                if (mode == Mode.FAMILY) {
                    if (room.sizeM2 == null && room.kind != RoomKind.FAMILY_ROOM && room.kind != RoomKind.SUITE) add("room size")
                    if (stay.hasLift == null) add("lift")
                    if (stay.mrtWalkMin == null) add("walk to MRT")
                }
                if (mode == Mode.LOW_COST_CONVENIENT) {
                    if (stay.lateCheckIn == null) add("late check-in")
                    if (!room.isPrivate && stay.lockers == null) add("lockers")
                }
            }
            StayChoice(
                stayId = stay.id, name = stay.name, type = stay.type, roomId = room.id, roomName = room.name, roomKind = room.kind,
                roomCount = count, nights = nights, total = money(room.pricePerNight * count * nights),
                minutesToVenue = minutesToVenue, mrtWalkMin = stay.mrtWalkMin, sizeM2 = room.sizeM2, hasLift = stay.hasLift,
                unknown = unknown, bookingLink = stay.bookingLink, source = stay.source, fetchedAt = stay.fetchedAt,
                description = ProviderText.sanitize(stay.description),
            )
        }
        if (choices.isEmpty()) return emptyList<StayChoice>() to "not enough rooms left"
        return choices to null
    }

    /** Lower is better. */
    fun stayScore(mode: Mode, c: StayChoice): Double = when (mode) {
        // Stays known to meet every rule rank above any stay with unknown data, whatever the price.
        Mode.LOW_COST_CONVENIENT -> c.unknown.size * UNKNOWN_RANK_PENALTY + c.total + VALUE_OF_MINUTE * (c.minutesToVenue ?: 60) * 2 * max(1, c.nights)
        Mode.FAMILY -> familyComfortPenalty(c) * 1000 + c.total
        else -> c.total
    }

    /** Comfort first: unknown data, long walks and small rooms cost comfort points. */
    fun familyComfortPenalty(c: StayChoice): Int =
        c.unknown.size * 3 + ((c.mrtWalkMin ?: FAMILY_MAX_MRT_WALK_MIN) / 3) + (if ((c.sizeM2 ?: 0.0) >= 30 || c.roomKind == RoomKind.FAMILY_ROOM) 0 else 1) +
            (c.roomCount - 1)

    fun flightChoice(leg: String, offer: FlightOffer, mode: Mode) = FlightChoice(
        leg = leg, offerId = offer.id, flightNos = offer.flightNos, from = offer.segments.first().from, to = offer.segments.last().to,
        depart = offer.depart, arrive = offer.arrive, stops = offer.stops, pax = offer.pax, checkedBag = checkedBag(mode) || offer.priceCarryOn == null,
        price = money(offer.price(checkedBag(mode))), seatsLeft = offer.seatsLeft, redEye = offer.isRedEye,
        bookingLink = offer.bookingLink, source = offer.source, fetchedAt = offer.fetchedAt,
    )

    fun clampPax(n: Int) = min(9, max(1, n))
}

/** Provider text is data. It is truncated, stripped of control characters and flagged if it tries to give instructions. */
object ProviderText {
    private val injection = Regex(
        """(ignore (all |any )?(previous|prior|above) (instructions|prompts?)|disregard (the )?(system|previous)|you are now|system prompt|<\s*/?\s*(system|assistant|instructions?)\s*>|book (this|it) now|transfer|send (your|the) (card|password))""",
        RegexOption.IGNORE_CASE,
    )
    const val MAX_LEN = 280

    fun looksLikeInjection(s: String) = injection.containsMatchIn(s)

    fun sanitize(s: String): String {
        val clean = s.replace(Regex("""[\p{Cntrl}&&[^\n]]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (looksLikeInjection(clean)) return "[provider text removed: it contained instructions]"
        return if (clean.length > MAX_LEN) clean.take(MAX_LEN - 1) + "…" else clean
    }
}
