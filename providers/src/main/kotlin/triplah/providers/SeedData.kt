package triplah.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import triplah.core.Airport
import triplah.core.CarrierType
import triplah.core.RoomOption
import triplah.core.StayType
import triplah.core.TripJson
import triplah.core.Venue
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class Schedule(
    val flightNo: String,
    val airline: String,
    val carrierType: CarrierType,
    val from: String,
    val to: String,
    val departLocal: String,
    val blockMin: Int,
    /** 1 = Monday ... 7 = Sunday. */
    val days: List<Int>,
    val baseFare: Double,
    val bagFee: Double,
    val capacity: Int,
)

@Serializable
data class SeedStay(
    val id: String,
    val name: String,
    val city: String,
    val nearVenue: String,
    val type: StayType,
    val lat: Double,
    val lon: Double,
    val rooms: List<RoomOption>,
    val lateCheckIn: Boolean? = null,
    val lockers: Boolean? = null,
    val hasLift: Boolean? = null,
    val mrtWalkMin: Int? = null,
    val description: String = "",
)

@Serializable
data class SeedHistory(val flightNo: String, val days: Int, val operated: Int, val cancelled: Int, val delayedOver60: Int)

/** The seed data set: 6 cities, fictional flights and stays. Used by the mock providers, tests and evals. */
class SeedData(val dir: File) {
    val airports: List<Airport> = read("airports.json", ListSerializer(Airport.serializer()))
    val venues: List<Venue> = read("venues.json", ListSerializer(Venue.serializer()))
    val schedules: List<Schedule> = read("schedules.json", ListSerializer(Schedule.serializer()))
    val stays: List<SeedStay> = read("stays.json", ListSerializer(SeedStay.serializer()))
    val history: List<SeedHistory> = read("history.json", ListSerializer(SeedHistory.serializer()))

    val airportByCode = airports.associateBy { it.code }
    val venueById = venues.associateBy { it.id }

    private fun <T> read(name: String, s: kotlinx.serialization.KSerializer<T>): T =
        TripJson.decodeFromString(s, File(dir, name).readText())

    companion object {
        /** Finds seed/ from TRIPLAH_SEED, the triplah.root property, or by walking up from the working directory. */
        fun locate(): SeedData {
            System.getenv("TRIPLAH_SEED")?.let { return SeedData(File(it)) }
            System.getProperty("triplah.root")?.let { val f = File(it, "seed"); if (File(f, "airports.json").exists()) return SeedData(f) }
            var d: File? = File("").absoluteFile
            while (d != null) {
                val f = File(d, "seed")
                if (File(f, "airports.json").exists()) return SeedData(f)
                d = d.parentFile
            }
            throw IllegalStateException("seed/ not found. Set TRIPLAH_SEED to the seed directory.")
        }

        @Volatile private var cached: SeedData? = null
        fun default(): SeedData = cached ?: synchronized(this) { cached ?: locate().also { cached = it } }
    }
}

object Geo {
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
