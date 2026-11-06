package triplah.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Times are always stored with their zone, e.g. 2026-11-20T08:15+08:00[Asia/Singapore]. */
object ZdtSerializer : KSerializer<ZonedDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("ZonedDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ZonedDateTime) =
        encoder.encodeString(value.format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
    override fun deserialize(decoder: Decoder): ZonedDateTime = parseZdt(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = try {
        LocalDate.parse(decoder.decodeString())
    } catch (e: Exception) {
        throw ValidationException("dates must look like 2026-11-20")
    }
}

/** Accepts "2026-11-20T08:15+08:00[Asia/Singapore]" or "2026-11-20T08:15+08:00". A time without an offset is rejected. */
fun parseZdt(s: String): ZonedDateTime = try {
    ZonedDateTime.parse(s.trim(), DateTimeFormatter.ISO_ZONED_DATE_TIME)
} catch (e: Exception) {
    throw ValidationException("time '$s' must include a UTC offset, e.g. 2026-11-20T08:15+08:00")
}

val TripJson = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

val PrettyJson = Json(TripJson) { prettyPrint = true }
