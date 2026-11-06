package triplah.evals

import kotlinx.coroutines.runBlocking
import triplah.api.ImportTimetableIn
import triplah.api.SearchFlightsIn
import triplah.api.SearchStaysIn
import triplah.api.Wiring
import triplah.core.Mode
import triplah.core.TripEvent
import triplah.core.TripRequest
import triplah.core.parseZdt
import java.io.File
import java.time.LocalDate

/**
 * Manual real-provider check (PLAN.md, Verification). Needs TRIPLAH_PROVIDERS=real and provider keys in the environment.
 *   ./gradlew :evals:liveCheck --args="2026-12-18 BKK th-qsncc"
 * 0. One call to every provider, with its error if it fails.
 * 1. Pain point 1 on a real date: prints the flights so you can compare them with Google Flights by hand.
 * 2. Speed per mode on real providers (target under 10 s), caches cleared per run.
 * 3. LiteAPI check (Open risk 2): how many stays are capsules/hostels and how many have room size, lift and MRT data.
 * Set TRIPLAH_RECORD_DIR=providers/src/test/resources/recorded/live to save raw responses for the provider tests.
 * Each run spends free-tier quota (roughly 30 SerpApi searches); the quota guard stops at 90%.
 */
fun main(args: Array<String>) {
    require(System.getenv("TRIPLAH_PROVIDERS") == "real") { "set TRIPLAH_PROVIDERS=real and the provider keys first" }
    val date = LocalDate.parse(args.getOrElse(0) { "2026-12-18" })
    val dest = args.getOrElse(1) { "BKK" }
    val venue = args.getOrElse(2) { "th-qsncc" }
    val staysOnly = "--stays-only" in args
    val skipSpeed = "--no-speed" in args || staysOnly
    val root = File(System.getProperty("triplah.root") ?: ".")
    val svc = Wiring.fromEnv()
    val hub = svc.hub
    println("Providers: flights=${hub.flights.map { it.name }} stays=${hub.stays.name} status=${hub.status.name} transit=${hub.transit.name}")
    svc.importTimetable(ImportTimetableIn("A", path = File(root, "seed/timetables/traveller-a.ics").path))
    val tz = svc.seed.airportByCode.getValue(dest).tz
    val offset = java.time.ZoneId.of(tz).rules.getOffset(date.atStartOfDay()).id
    val ev = TripEvent("Live check", parseZdt("${date}T14:00$offset"), parseZdt("${date}T17:00$offset"), venue)
    val city = svc.seed.airportByCode.getValue(dest).city

    if (!staysOnly) {
    println("\n== 0. Every provider once ==")
    val cal = runBlocking { hub.calendar("SIN", dest, date.withDayOfMonth(1), date.withDayOfMonth(1).plusMonths(1).minusDays(1)) }
    println("  calendar: ${cal.items.size} dates" + (cal.items.minByOrNull { it.pricePerSeat }?.let { ", cheapest ${it.date} SGD ${it.pricePerSeat} (${it.source}, fetched ${it.fetchedAt})" } ?: "") + cal.errors.joinToString("") { "\n    error: $it" })
    val stays0 = runBlocking { hub.searchStays(triplah.providers.StayQuery(city, date, date.plusDays(1), 1, svc.seed.venueById[venue]?.lat, svc.seed.venueById[venue]?.lon)) }
    println("  stays: ${stays0.items.size}" + stays0.errors.joinToString("") { "\n    error: $it" })
    val ntu = svc.seed.venueById.getValue("sg-ntu"); val sin = svc.seed.airportByCode.getValue("SIN")
    val transit = runBlocking { runCatching { hub.transit.minutes(ntu.lat, ntu.lon, sin.lat, sin.lon, "Singapore") } }
    println("  transit NTU -> Changi: " + transit.fold({ "$it min (${hub.transit.name})" }, { "error: ${it.message}" }))

    println("\n== 1. Raw search $date SIN -> $dest (compare with Google Flights) ==")
    val raw = runBlocking { svc.searchFlights(SearchFlightsIn("SIN", dest, date, 1, 0, 10)) }
    raw.offers.forEach { println("  ${it.flightNos} ${it.depart.toLocalTime()} -> ${it.arrive.toLocalTime()}  SGD ${it.priceCarryOn ?: it.priceWithBag}  (${it.source})") }
    raw.errors.forEach { println("  error: $it") }

    raw.offers.firstOrNull()?.let { o ->
        val st = runBlocking { hub.flightStatus(o.segments.first().flightNo, o.depart.toLocalDate()) }
        println("  status ${o.segments.first().flightNo}: " + st.fold({ "${it.state}, scheduled ${it.scheduledDepart.toLocalTime()} (${it.source})" }, { "error: ${it.message}" }))
    }
    }

    if (!skipSpeed) {
        println("\n== 2. Speed per mode (target < 10 s) ==")
        for (mode in listOf(Mode.FAST, Mode.LOW_COST)) {
            val times = (1..3).map {
                hub.clearCaches()
                val t = System.nanoTime()
                val r = runBlocking { svc.planTrip(TripRequest(mode = mode, origin = "SIN", destination = dest, travellers = listOf("A"), event = ev)) }
                if (it == 1) {
                    println("  $mode: ${r.status}. ${r.summary.lineSequence().firstOrNull()}")
                    r.notes.filter { n -> n.startsWith("Provider problem") }.distinct().forEach { n -> println("    $n") }
                }
                (System.nanoTime() - t) / 1e6
            }.sorted()
            println("  $mode p50 ${"%.0f".format(times[1])} ms, max ${"%.0f".format(times[2])} ms")
        }
    }

    if ("--convenient" in args) {
        println("\n== Pain point 2 on real data: LOW_COST_CONVENIENT, Travellers A + B, $date 09:00 to ${date.plusDays(1)} 15:00 ==")
        svc.importTimetable(ImportTimetableIn("B", path = File(root, "seed/timetables/traveller-b.json").path))
        val hack = TripEvent("Live hackathon", parseZdt("${date}T09:00$offset"), parseZdt("${date.plusDays(1)}T15:00$offset"), venue)
        val r = runBlocking { svc.planTrip(TripRequest(mode = Mode.LOW_COST_CONVENIENT, origin = "SIN", destination = dest, travellers = listOf("A", "B"), event = hack)) }
        println("  status ${r.status}")
        r.plans.forEach { p ->
            println("  ${p.label}: ${p.flights.joinToString(" / ") { "${it.flightNos} ${it.depart.toLocalDate()} ${it.depart.toLocalTime()}" }}; " +
                "stay ${p.stay?.name} (${p.stay?.type}, ${p.stay?.roomCount} x ${p.stay?.roomName}, ${p.stay?.minutesToVenue} min) unknown=${p.stay?.unknown}; total SGD ${p.costs.total}")
        }
        r.stayComparison.forEach { println("  compare: ${it.option} at ${it.stayName}: SGD ${it.total}") }
        r.rejected.entries.sortedByDescending { it.value }.take(5).forEach { println("  rejected ${it.value}: ${it.key}") }
        r.notes.filter { it.startsWith("Provider problem") }.distinct().forEach { println("  $it") }
        return
    }

    println("\n== 3. Stay data coverage (Open risk 2) ==")
    val res = runBlocking { svc.searchStays(SearchStaysIn(city, date, date.plusDays(1), 1, venue, 50)) }
    val stays = res.stays
    val n = stays.size.coerceAtLeast(1)
    println("  ${stays.size} stays; capsule/hostel ${stays.count { it.stay.type.name in setOf("CAPSULE", "HOSTEL") }}; " +
        "room size known ${stays.count { s -> s.stay.rooms.any { it.sizeM2 != null } } * 100 / n}%; lift known ${stays.count { it.stay.hasLift != null } * 100 / n}%; " +
        "MRT walk known ${stays.count { it.stay.mrtWalkMin != null } * 100 / n}%; within 15 min of venue ${stays.count { (it.minutesToVenue ?: 99) <= 15 }}")
    val budget = stays.filter { it.stay.type.name in setOf("CAPSULE", "HOSTEL") }
    println("  capsules/hostels: ${budget.size}; with 24-hour front desk ${budget.count { it.stay.lateCheckIn == true }}; with lockers ${budget.count { it.stay.lockers == true }}; " +
        "with shared beds or pods ${budget.count { s -> s.stay.rooms.any { it.kind.name in setOf("POD", "DORM_BED") } }}; " +
        "meeting every LOW_COST_CONVENIENT rule (15 min, late check-in, lockers, bed) ${budget.count { s -> (s.minutesToVenue ?: 99) <= 15 && s.stay.lateCheckIn == true && s.stay.lockers == true && s.stay.rooms.any { it.kind.name in setOf("POD", "DORM_BED") } }}")
    budget.take(5).forEach { println("    ${it.stay.name} [${it.stay.type}] ${it.minutesToVenue} min; rooms: ${it.stay.rooms.take(3).joinToString { r -> "${r.name} (${r.kind}, SGD ${r.pricePerNight})" }}") }
    res.errors.forEach { println("  error: $it") }
}
