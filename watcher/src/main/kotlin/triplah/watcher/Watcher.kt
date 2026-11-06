package triplah.watcher

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import triplah.api.TripService
import triplah.api.Wiring
import triplah.core.Buffers
import triplah.core.Explain
import triplah.core.FlightState
import triplah.core.Planner
import triplah.core.Pricing
import triplah.core.Recheck
import triplah.core.money
import triplah.providers.FlightQuery
import triplah.providers.HttpGateway
import triplah.providers.QuotaGuard
import java.time.Duration
import java.time.ZonedDateTime

fun interface Alerter {
    fun send(kind: String, message: String)
}

class ConsoleAlerter : Alerter {
    override fun send(kind: String, message: String) = System.err.println("[$kind] $message")
}

/** Telegram bot alerts. Token and chat id come from the environment only. */
class TelegramAlerter(private val gw: HttpGateway, private val token: String, private val chatId: String) : Alerter {
    override fun send(kind: String, message: String) {
        val body = buildJsonObject { put("chat_id", chatId); put("text", "TripLah ($kind): $message") }.toString()
        runBlocking { gw.post("telegram", "https://api.telegram.org/bot$token/sendMessage", body) }
    }
}

data class RunReport(val priceChecks: Int, val flightChecks: Int, val alerts: List<String>)

/**
 * Scheduled checks. Run [runOnce] from cron / Task Scheduler, or [loop].
 * - Price watch: at most twice a day per watch. Alerts below target, and on the book-by date whatever the price.
 * - Flight recheck: daily, every 6 h in the last week, hourly in the last 24 h. A move of 15+ minutes
 *   or a cancellation re-runs the timetable check and the alert names the clashing class and alternatives.
 */
class Watcher(private val svc: TripService, private val alerters: List<Alerter>, private val buffers: Buffers = Buffers()) {
    private val store get() = svc.store

    private fun alert(kind: String, msg: String, out: MutableList<String>) {
        store.addAlert(kind, msg); out += msg
        alerters.forEach { runCatching { it.send(kind, msg) }.onFailure { e -> System.err.println("alert failed: ${e.message}") } }
    }

    fun runOnce(now: ZonedDateTime): RunReport = runBlocking {
        val alerts = mutableListOf<String>()
        var priceChecks = 0; var flightChecks = 0

        for (w in store.priceWatches()) {
            if (now.toLocalDate().isAfter(w.date)) { store.updatePriceWatch(w.id, w.lastPrice ?: 0.0, now.toString(), "EXPIRED"); continue }
            val last = w.lastChecked?.let { ZonedDateTime.parse(it) }
            if (last != null && Duration.between(last, now) < Duration.ofHours(12) && now.toLocalDate().isBefore(w.bookBy)) continue
            priceChecks++
            val res = svc.hub.searchFlights(FlightQuery(w.from, w.to, w.date, w.pax, 1), fresh = true)
            val best = res.items.minByOrNull { it.price(false) } ?: continue
            val price = money(best.price(false))
            store.recordPrice(w.from, w.to, w.date, money(price / w.pax), svc.clock.instant())
            val advice = Pricing.buyOrWait(price, store.routePrices(w.from, w.to).map { it * w.pax }, now.toLocalDate(), w.date, w.bookBy)
            when {
                !now.toLocalDate().isBefore(w.bookBy) -> {
                    alert("book-by", "Book-by date for ${w.from}→${w.to} on ${w.date} is today. Cheapest now: ${best.flightNos} at ${Explain.fmt(price)} (target ${Explain.fmt(w.targetPrice)}). ${best.bookingLink}", alerts)
                    store.updatePriceWatch(w.id, price, now.toString(), "DONE")
                }
                price <= w.targetPrice -> {
                    alert("price-drop", "${w.from}→${w.to} on ${w.date}: ${best.flightNos} is ${Explain.fmt(price)}, below your target ${Explain.fmt(w.targetPrice)}. ${advice.label} ${best.bookingLink}", alerts)
                    store.updatePriceWatch(w.id, price, now.toString(), "TARGET_HIT")
                }
                else -> store.updatePriceWatch(w.id, price, now.toString(), "ACTIVE")
            }
        }

        for (fw in store.flightWatches()) {
            if (fw.nextCheck.isAfter(now)) continue
            flightChecks++
            val f = fw.flight
            val res = svc.hub.flightStatus(f.flightNo, f.date)
            val status = res.getOrNull()
            if (status == null) {
                store.updateFlightWatch(fw.id, now.plusHours(1), "ERROR", res.exceptionOrNull()?.message ?: "status unavailable"); continue
            }
            val delay = Duration.between(status.scheduledDepart, status.estimatedDepart ?: status.scheduledDepart).toMinutes()
            store.recordFlight(f.flightNo, f.date, status.state.name, delay)
            val tts = f.travellers.mapNotNull { store.timetable(it) }
            val r = Recheck.evaluate(f, status, tts, buffers)
            val next = if (status.state in setOf(FlightState.DEPARTED, FlightState.LANDED, FlightState.CANCELLED)) null else Recheck.nextCheck(now, f.depart)
            if (r.changed && r.message != fw.lastMessage) {
                val alts = if (r.cancelled || r.clashes.isNotEmpty()) svc.alternativesFor(f, tts, buffers) else emptyList()
                val altText = if (alts.isEmpty()) (if (r.cancelled || r.clashes.isNotEmpty()) " No alternative found that keeps every must-attend class." else "")
                else " Alternatives: " + alts.joinToString("; ") { "${it.flightNos} ${Planner.hhmm(it.depart)}→${Planner.hhmm(it.arrive)} ${Explain.fmt(it.price(false))}" } + "."
                alert(if (r.cancelled) "cancelled" else "retimed", r.message + altText, alerts)
            }
            store.updateFlightWatch(fw.id, next, status.state.name, r.message)
        }
        RunReport(priceChecks, flightChecks, alerts)
    }
}

fun main(args: Array<String>) {
    val svc = Wiring.fromEnv()
    val env = System.getenv()
    val alerters = buildList<Alerter> {
        add(ConsoleAlerter())
        if (env["TELEGRAM_BOT_TOKEN"] != null && env["TELEGRAM_CHAT_ID"] != null)
            add(TelegramAlerter(HttpGateway(HttpGateway.DEFAULT_HOSTS, QuotaGuard(emptyMap())), env.getValue("TELEGRAM_BOT_TOKEN"), env.getValue("TELEGRAM_CHAT_ID")))
    }
    val w = Watcher(svc, alerters)
    val every = args.indexOf("--every-minutes").takeIf { it >= 0 }?.let { args[it + 1].toLong() }
    do {
        val r = w.runOnce(ZonedDateTime.now(svc.clock))
        System.err.println("watcher: ${r.priceChecks} price checks, ${r.flightChecks} flight checks, ${r.alerts.size} alerts")
        if (every != null) Thread.sleep(every * 60_000)
    } while (every != null)
}
