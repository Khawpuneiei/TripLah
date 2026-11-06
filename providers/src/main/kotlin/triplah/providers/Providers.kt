@file:UseSerializers(ZdtSerializer::class, LocalDateSerializer::class, InstantSerializer::class)

package triplah.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import triplah.core.FlightHistory
import triplah.core.FlightOffer
import triplah.core.FlightStatus
import triplah.core.InstantSerializer
import triplah.core.LocalDateSerializer
import triplah.core.StayOffer
import triplah.core.ZdtSerializer
import java.time.Instant
import java.time.LocalDate

data class FlightQuery(val from: String, val to: String, val date: LocalDate, val pax: Int, val maxStops: Int = 1)

@Serializable
data class CalendarPrice(
    val from: String,
    val to: String,
    val date: LocalDate,
    /** Cheapest price per seat seen for the date, SGD. Cached: may be up to 7 days old. */
    val pricePerSeat: Double,
    val flightNo: String? = null,
    val source: String,
    val fetchedAt: Instant,
)

data class StayQuery(val city: String, val checkIn: LocalDate, val checkOut: LocalDate, val guests: Int, val nearLat: Double? = null, val nearLon: Double? = null)

class ProviderUnavailable(message: String) : RuntimeException(message)

interface FlightProvider {
    val name: String
    suspend fun search(q: FlightQuery): List<FlightOffer>
    /** Cheapest cached price per date. Not every provider supports it. */
    suspend fun calendar(from: String, to: String, start: LocalDate, end: LocalDate): List<CalendarPrice> =
        throw ProviderUnavailable("$name has no price calendar")
}

interface StayProvider {
    val name: String
    suspend fun search(q: StayQuery): List<StayOffer>
}

interface StatusProvider {
    val name: String
    suspend fun status(flightNo: String, date: LocalDate): FlightStatus
    /** Last 30-90 days for this flight number. Null when there is no history. */
    suspend fun history(flightNo: String): FlightHistory?
}

interface TransitProvider {
    val name: String
    /** Door-to-door public transport minutes, or a taxi estimate where there is no transit. */
    suspend fun minutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, city: String): Int
}
