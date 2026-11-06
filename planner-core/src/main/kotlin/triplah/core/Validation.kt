package triplah.core

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** Tool inputs are validated before any provider is called. */
object Validate {
    const val MAX_PAX = 9
    const val MAX_BUDGET = 50_000.0
    private val iata = Regex("^[A-Z]{3}$")
    private val travellerId = Regex("^[A-Z]$")

    fun airport(code: String, known: Set<String>): String {
        val c = code.trim().uppercase()
        if (!iata.matches(c)) throw ValidationException("'$code' is not a 3-letter airport code")
        if (c !in known) throw ValidationException("airport $c is not supported (supported: ${known.sorted().joinToString()})")
        return c
    }

    fun pax(n: Int): Int {
        if (n !in 1..MAX_PAX) throw ValidationException("passengers must be 1 to $MAX_PAX, got $n")
        return n
    }

    fun budget(b: Double?): Double? {
        if (b == null) return null
        if (b.isNaN() || b <= 0 || b > MAX_BUDGET) throw ValidationException("budget must be between 1 and ${MAX_BUDGET.toInt()} SGD")
        return b
    }

    fun traveller(id: String): String {
        val t = id.trim().uppercase().removePrefix("TRAVELLER ").trim()
        if (!travellerId.matches(t)) throw ValidationException("traveller id must be one letter, e.g. A or B (no names or ID numbers)")
        return t
    }

    fun date(d: LocalDate, today: LocalDate) {
        if (d.isBefore(today.minusDays(1))) throw ValidationException("date $d is in the past")
        if (d.isAfter(today.plusDays(366))) throw ValidationException("date $d is more than a year away")
    }

    fun buffers(b: Buffers) {
        listOf(b.campusToAirportMin, b.checkInBeforeDepartureMin, b.immigrationOnArrivalMin).forEach {
            if (it !in 0..480) throw ValidationException("buffers must be 0 to 480 minutes")
        }
    }

    /** No passport or ID numbers: refuse anything that looks like one in free text. */
    private val idLike = Regex("""\b([A-Z]\d{7}[A-Z]|[A-Z]{1,2}\d{6,9}|\d{13})\b""")
    fun noIdNumbers(s: String, field: String) {
        if (idLike.containsMatchIn(s)) throw ValidationException("$field looks like it contains an ID or passport number. TripLah does not store those.")
    }

    fun request(r: TripRequest, known: Set<String>, now: ZonedDateTime): TripRequest {
        val origin = airport(r.origin, known)
        val dest = airport(r.destination, known)
        if (origin == dest) throw ValidationException("origin and destination are the same")
        val travellers = r.travellers.map(::traveller).distinct()
        budget(r.budget)
        buffers(r.buffers)
        r.passengers?.let(::pax)
        when (r.painPoint) {
            PainPoint.EVENT_TRIP -> {
                val ev = r.event ?: throw ValidationException("EVENT_TRIP needs event {name, start, end}")
                if (!ev.end.isAfter(ev.start)) throw ValidationException("event ends before it starts")
                if (Duration.between(ev.start, ev.end).toDays() > 7) throw ValidationException("trips are 1 day to 1 week")
                noIdNumbers(ev.name, "event name")
                date(ev.start.toLocalDate(), now.toLocalDate())
                pax(r.passengers ?: travellers.size.coerceAtLeast(1))
            }
            PainPoint.FAMILY_VISIT -> {
                val fam = r.familySize ?: throw ValidationException("FAMILY_VISIT needs familySize")
                pax(fam + (if (r.hostTraveller != null) 1 else 0))
                val d = r.arriveOn ?: throw ValidationException("FAMILY_VISIT needs arriveOn")
                date(d, now.toLocalDate())
                val n = r.nights ?: throw ValidationException("FAMILY_VISIT needs nights")
                if (n !in 1..7) throw ValidationException("nights must be 1 to 7")
                r.hostReturnOn?.let { if (it.isBefore(d.plusDays(n.toLong()))) throw ValidationException("hostReturnOn is before the family flies home") }
            }
        }
        return r.copy(origin = origin, destination = dest, travellers = travellers, hostTraveller = r.hostTraveller?.let(::traveller))
    }
}
