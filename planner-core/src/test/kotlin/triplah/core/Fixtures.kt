package triplah.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val SGT: ZoneId = ZoneId.of("Asia/Singapore")
val ICT: ZoneId = ZoneId.of("Asia/Bangkok")
val FETCHED: Instant = Instant.parse("2026-11-10T00:00:00Z")

fun at(date: String, time: String, zone: ZoneId = SGT): ZonedDateTime = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone)

fun seg(no: String, from: String, to: String, dep: ZonedDateTime, minutes: Long, type: CarrierType = CarrierType.LOW_COST): Segment {
    val toZone = if (to == "BKK") ICT else SGT
    return Segment(no, no.take(2), type, from, to, dep, dep.plusMinutes(minutes).withZoneSameInstant(toZone))
}

fun offer(
    vararg segs: Segment,
    pax: Int = 1,
    carry: Double? = 100.0,
    bag: Double = 140.0,
    seats: Int? = 9,
    single: Boolean = true,
) = FlightOffer(
    id = segs.joinToString("-") { it.flightNo } + ":" + segs.first().depart.toLocalDate() + ":$pax",
    segments = segs.toList(), pax = pax, priceCarryOn = carry, priceWithBag = bag, seatsLeft = seats, singleTicket = single,
    bookingLink = "https://example.test", source = "test", fetchedAt = FETCHED,
)

fun stay(
    id: String,
    type: StayType,
    vararg rooms: RoomOption,
    late: Boolean? = true,
    lockers: Boolean? = true,
    lift: Boolean? = true,
    mrt: Int? = 5,
    desc: String = "Nice place.",
) = StayOffer(id, "Stay $id", "Bangkok", type, 13.72, 100.55, rooms.toList(), late, lockers, lift, mrt, desc, bookingLink = "https://example.test/s", source = "test", fetchedAt = FETCHED)

fun room(id: String, kind: RoomKind, sleeps: Int, price: Double, size: Double? = null) = RoomOption(id, "$kind room", kind, sleeps, size, price)

fun ev(title: String, start: ZonedDateTime, hours: Long, a: Attendance) = Event(title, start, start.plusHours(hours), a)

fun tt(id: String, vararg events: Event) = Timetable(id, "Asia/Singapore", events.toList())
