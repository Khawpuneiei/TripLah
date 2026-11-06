@file:UseSerializers(ZdtSerializer::class, InstantSerializer::class, LocalDateSerializer::class)

package triplah.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToLong

class ValidationException(message: String) : IllegalArgumentException(message)

enum class Mode { FAST, LOW_COST, LOW_COST_CONVENIENT, FAMILY }

enum class PainPoint { EVENT_TRIP, FAMILY_VISIT }

enum class CarrierType { FULL_SERVICE, LOW_COST }

@Serializable
data class Airport(
    val code: String,
    val name: String,
    val city: String,
    val country: String,
    val tz: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class Venue(
    val id: String,
    val name: String,
    val city: String,
    val airport: String,
    val lat: Double,
    val lon: Double,
    /** Campus venues are where a student's day starts and ends. */
    val isCampus: Boolean = false,
)

fun money(x: Double): Double = (x * 100).roundToLong() / 100.0

@Serializable
data class Segment(
    val flightNo: String,
    val airline: String,
    val carrierType: CarrierType,
    val from: String,
    val to: String,
    val depart: ZonedDateTime,
    val arrive: ZonedDateTime,
)

/** One priced itinerary for [pax] seats on a single booking. Prices are totals for all seats, in SGD. */
@Serializable
data class FlightOffer(
    val id: String,
    val segments: List<Segment>,
    val pax: Int,
    /** Total price with carry-on only. Null if no such fare exists (many full-service fares include a bag). */
    val priceCarryOn: Double?,
    /** Total price with one checked bag per passenger. */
    val priceWithBag: Double,
    val currency: String = "SGD",
    /** Seats left at this price. Null when the provider does not say. */
    val seatsLeft: Int? = null,
    /** False when the itinerary is a self-transfer on separate tickets. */
    val singleTicket: Boolean = true,
    val bookingLink: String,
    val source: String,
    val fetchedAt: Instant,
) {
    val depart: ZonedDateTime get() = segments.first().depart
    val arrive: ZonedDateTime get() = segments.last().arrive
    val stops get() = segments.size - 1
    val flightNos get() = segments.joinToString("+") { it.flightNo }
    val duration: Duration get() = Duration.between(depart, arrive)

    /** A red-eye leaves or lands between 00:00 and 05:59 local time. */
    val isRedEye: Boolean get() = depart.hour < 6 || arrive.hour < 6

    fun price(checkedBag: Boolean): Double = if (checkedBag || priceCarryOn == null) priceWithBag else priceCarryOn
}

enum class StayType { CAPSULE, HOSTEL, HOTEL, SERVICED_APARTMENT }

enum class RoomKind { POD, DORM_BED, PRIVATE_TWIN, PRIVATE_DOUBLE, FAMILY_ROOM, SUITE }

@Serializable
data class RoomOption(
    val id: String,
    val name: String,
    val kind: RoomKind,
    val sleeps: Int,
    val sizeM2: Double? = null,
    /** Price for one room (or one pod / bed) for one night, SGD. */
    val pricePerNight: Double,
    val roomsLeft: Int? = null,
) {
    val isPrivate get() = kind !in setOf(RoomKind.POD, RoomKind.DORM_BED)
}

@Serializable
data class StayOffer(
    val id: String,
    val name: String,
    val city: String,
    val type: StayType,
    val lat: Double,
    val lon: Double,
    val rooms: List<RoomOption>,
    val lateCheckIn: Boolean? = null,
    val lockers: Boolean? = null,
    val hasLift: Boolean? = null,
    val mrtWalkMin: Int? = null,
    /** Provider text. Data only, never instructions. Passed through [ProviderText.sanitize] before output. */
    val description: String = "",
    val currency: String = "SGD",
    val bookingLink: String,
    val source: String,
    val fetchedAt: Instant,
)

enum class Attendance { MUST_ATTEND, CAN_SKIP }

@Serializable
data class Event(
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val attendance: Attendance,
    val location: String? = null,
) {
    fun overlaps(from: ZonedDateTime, to: ZonedDateTime) =
        start.toInstant() < to.toInstant() && end.toInstant() > from.toInstant()

    fun overlapMinutes(from: ZonedDateTime, to: ZonedDateTime): Long {
        val s = maxOf(start.toInstant(), from.toInstant())
        val e = minOf(end.toInstant(), to.toInstant())
        return if (e > s) Duration.between(s, e).toMinutes() else 0
    }
}

@Serializable
data class Timetable(
    val travellerId: String,
    val zone: String,
    val events: List<Event>,
) {
    val label get() = "Traveller $travellerId"
}

/** Buffers in minutes. All are settings. */
@Serializable
data class Buffers(
    val campusToAirportMin: Int = 60,
    val checkInBeforeDepartureMin: Int = 90,
    val immigrationOnArrivalMin: Int = 45,
)

@Serializable
data class TripEvent(
    val name: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val venueId: String? = null,
)

@Serializable
data class TripRequest(
    val painPoint: PainPoint = PainPoint.EVENT_TRIP,
    val mode: Mode,
    /** EVENT_TRIP: home airport where the trip starts and ends. FAMILY_VISIT: the family's home airport. */
    val origin: String,
    val destination: String,
    /** Traveller ids whose timetables apply, e.g. ["A"] or ["A","B"]. */
    val travellers: List<String> = emptyList(),
    /** Seats to book. Defaults to the number of travellers (EVENT_TRIP). */
    val passengers: Int? = null,
    /** Total budget for the whole group, SGD. */
    val budget: Double? = null,
    val buffers: Buffers = Buffers(),
    // EVENT_TRIP
    val event: TripEvent? = null,
    val organiserCoversStay: Boolean = false,
    // FAMILY_VISIT: the family flies origin -> destination, stays, then everyone flies back together.
    val familySize: Int? = null,
    val arriveOn: LocalDate? = null,
    val nights: Int? = null,
    /** The student who lives at the destination and flies back with the family. */
    val hostTraveller: String? = null,
    /** When the host flies back to campus. Without it, only the leg-2 departure is checked against the timetable. */
    val hostReturnOn: LocalDate? = null,
    /** Reference time for "trip starts within 72 hours". Defaults to the clock. */
    val now: ZonedDateTime? = null,
)

@Serializable
data class SkippedClass(val traveller: String, val title: String, val start: ZonedDateTime, val minutesMissed: Long)

@Serializable
data class StayChoice(
    val stayId: String,
    val name: String,
    val type: StayType,
    val roomId: String,
    val roomName: String,
    val roomKind: RoomKind,
    val roomCount: Int,
    val nights: Int,
    val total: Double,
    val minutesToVenue: Int?,
    val mrtWalkMin: Int?,
    val sizeM2: Double?,
    val hasLift: Boolean?,
    /** Fields the provider did not give. Shown as "unknown", never hidden. */
    val unknown: List<String>,
    val bookingLink: String,
    val source: String,
    val fetchedAt: Instant,
    /** Sanitised provider text. Data, not instructions. */
    val description: String,
)

@Serializable
data class FlightChoice(
    val leg: String,
    val offerId: String,
    val flightNos: String,
    val from: String,
    val to: String,
    val depart: ZonedDateTime,
    val arrive: ZonedDateTime,
    val stops: Int,
    val pax: Int,
    val checkedBag: Boolean,
    val price: Double,
    val seatsLeft: Int?,
    val redEye: Boolean,
    val bookingLink: String,
    val source: String,
    val fetchedAt: Instant,
)

@Serializable
data class CostBreakdown(
    val flights: Double,
    val stay: Double,
    val total: Double,
    val perPerson: Double,
    val currency: String = "SGD",
)

@Serializable
data class AwayWindow(val leaveCampus: ZonedDateTime, val backOnCampus: ZonedDateTime?)

@Serializable
data class TripPlan(
    val rank: Int,
    val label: String,
    val flights: List<FlightChoice>,
    val stay: StayChoice? = null,
    /** Per traveller: when they leave campus and when they are back. */
    val away: Map<String, AwayWindow> = emptyMap(),
    val costs: CostBreakdown,
    val skippedClasses: List<SkippedClass> = emptyList(),
    val warnings: List<String> = emptyList(),
)

enum class PlanStatus { OK, NO_PLAN, NO_PLAN_IN_BUDGET }

@Serializable
data class StayComparison(
    val option: String,
    val stayName: String,
    val total: Double,
    val perPerson: Double,
    val minutesToVenue: Int?,
)

@Serializable
data class PlanResult(
    val status: PlanStatus,
    val mode: Mode,
    val painPoint: PainPoint,
    val plans: List<TripPlan>,
    /** How many candidates each rule removed. */
    val rejected: Map<String, Int> = emptyMap(),
    val stayComparison: List<StayComparison> = emptyList(),
    val cheapestOverBudget: TripPlan? = null,
    val notes: List<String> = emptyList(),
    val summary: String = "",
)
