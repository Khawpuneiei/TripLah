package triplah.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import triplah.core.FlightHistory
import triplah.core.FlightOffer
import triplah.core.FlightStatus
import triplah.core.StayOffer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class QuotaExceeded(message: String) : RuntimeException(message)
class HostNotAllowed(message: String) : SecurityException(message)

interface QuotaStore {
    fun used(provider: String, month: String): Int
    fun add(provider: String, month: String)
}

class InMemoryQuotaStore : QuotaStore {
    private val m = ConcurrentHashMap<String, Int>()
    override fun used(provider: String, month: String) = m["$provider|$month"] ?: 0
    override fun add(provider: String, month: String) { m.merge("$provider|$month", 1, Int::plus) }
}

/**
 * Stops calling a provider before its free tier runs out. [limits] is calls per month;
 * calls stop at [reserve] of the limit so a few are left for manual checks.
 */
class QuotaGuard(
    private val limits: Map<String, Int>,
    private val store: QuotaStore = InMemoryQuotaStore(),
    private val clock: Clock = Clock.systemUTC(),
    private val reserve: Double = 0.9,
) {
    private fun month() = YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString()

    @Synchronized
    fun acquire(provider: String) {
        val limit = limits[provider] ?: return
        val stopAt = (limit * reserve).toInt()
        val used = store.used(provider, month())
        if (used >= stopAt) throw QuotaExceeded("$provider: $used of $limit calls used this month; stopped at $stopAt to protect the free tier")
        store.add(provider, month())
    }

    fun remaining(provider: String): Int? = limits[provider]?.let { (it * reserve).toInt() - store.used(provider, month()) }
}

/** The only way real adapters reach the network: host allow-list, 5-second timeout, quota check. */
class HttpGateway(
    private val allowedHosts: Set<String>,
    private val quota: QuotaGuard,
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()

    fun checkHost(url: String) {
        val host = URI(url).host?.lowercase() ?: throw HostNotAllowed("no host in $url")
        if (URI(url).scheme != "https") throw HostNotAllowed("only https is allowed: $url")
        if (host !in allowedHosts) throw HostNotAllowed("host $host is not on the provider allow-list")
    }

    suspend fun get(provider: String, url: String, headers: Map<String, String> = emptyMap()): String = request(provider, url, headers, null)

    suspend fun post(provider: String, url: String, body: String, headers: Map<String, String> = emptyMap()): String = request(provider, url, headers, body)

    private suspend fun request(provider: String, url: String, headers: Map<String, String>, body: String?): String = withContext(Dispatchers.IO) {
        checkHost(url)
        quota.acquire(provider)
        val b = HttpRequest.newBuilder(URI(url)).timeout(timeout)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)) else b.GET()
        val req = b.build()
        var res = client.send(req, HttpResponse.BodyHandlers.ofString())
        // Rate limited: wait as asked (at most 5 s) and retry, twice at most. Each retry counts against the quota.
        var tries = 0
        while (res.statusCode() == 429 && tries < 2) {
            tries++
            val wait = res.headers().firstValue("retry-after").map { it.toLongOrNull() }.orElse(null) ?: (tries.toLong())
            Thread.sleep(wait.coerceIn(1, 5) * 1000)
            quota.acquire(provider)
            res = client.send(req, HttpResponse.BodyHandlers.ofString())
        }
        if (res.statusCode() !in 200..299) throw ProviderUnavailable("$provider returned HTTP ${res.statusCode()}")
        record(provider, url, res.body())
        res.body()
    }

    /** With TRIPLAH_RECORD_DIR set, raw responses are saved for provider tests. Keys and tokens are stripped from the saved URL. */
    private fun record(provider: String, url: String, body: String) {
        val dir = System.getenv("TRIPLAH_RECORD_DIR") ?: return
        val safeUrl = url.replace(Regex("""(?i)(api_key|token|key)=[^&]*"""), "$1=REDACTED")
        val f = java.io.File(dir, "${provider}_${System.currentTimeMillis()}.json")
        f.parentFile.mkdirs()
        f.writeText("{\"_recordedFrom\": ${kotlinx.serialization.json.JsonPrimitive(safeUrl)}, \"body\": $body}" + "\n")
    }

    companion object {
        val DEFAULT_HOSTS = setOf(
            "serpapi.com", "api.travelpayouts.com", "api.liteapi.travel", "aerodatabox.p.rapidapi.com",
            "aeroapi.flightaware.com", "www.onemap.gov.sg", "api.telegram.org",
        )
    }
}

private class TtlCache<K, V>(private val ttl: Duration, private val clock: Clock) {
    private data class Entry<V>(val value: V, val at: Instant)
    private val m = ConcurrentHashMap<K, Entry<V>>()
    fun get(k: K): V? = m[k]?.takeIf { Duration.between(it.at, clock.instant()) < ttl }?.value
    fun put(k: K, v: V) { m[k] = Entry(v, clock.instant()) }
    fun clear() = m.clear()
}

data class SearchResult<T>(val items: List<T>, val errors: List<String>)

/**
 * Calls providers in parallel with a timeout each, and caches results.
 * Offers are cached 12 minutes (within the 10-15 minute rule); transit times 24 hours.
 */
class ProviderHub(
    val flights: List<FlightProvider>,
    val stays: StayProvider,
    val status: StatusProvider,
    val transit: TransitProvider,
    val clock: Clock = Clock.systemUTC(),
    private val timeoutMs: Long = 5_000,
    val offerTtl: Duration = Duration.ofMinutes(12),
) {
    private val flightCache = TtlCache<FlightQuery, SearchResult<FlightOffer>>(offerTtl, clock)
    private val stayCache = TtlCache<StayQuery, SearchResult<StayOffer>>(offerTtl, clock)
    private val transitCache = TtlCache<String, Int>(Duration.ofHours(24), clock)
    private val calendarCache = TtlCache<String, SearchResult<CalendarPrice>>(Duration.ofHours(6), clock)
    @Volatile var providerCalls = 0; private set

    private suspend fun <T> guarded(label: String, block: suspend () -> T): Result<T> = try {
        providerCalls++
        Result.success(withTimeout(timeoutMs) { block() })
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(ProviderUnavailable("$label timed out after ${timeoutMs / 1000}s"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun searchFlights(q: FlightQuery, fresh: Boolean = false): SearchResult<FlightOffer> {
        if (!fresh) flightCache.get(q)?.let { return it }
        val results = coroutineScope { flights.map { p -> async { p.name to guarded(p.name) { p.search(q) } } }.awaitAll() }
        val r = SearchResult(results.flatMap { it.second.getOrNull().orEmpty() }, results.mapNotNull { (n, res) -> res.exceptionOrNull()?.let { "$n: ${it.message}" } })
        if (r.errors.isEmpty() || r.items.isNotEmpty()) flightCache.put(q, r)
        return r
    }

    suspend fun searchStays(q: StayQuery): SearchResult<StayOffer> {
        stayCache.get(q)?.let { return it }
        val res = guarded(stays.name) { stays.search(q) }
        val r = SearchResult(res.getOrNull().orEmpty(), listOfNotNull(res.exceptionOrNull()?.let { "${stays.name}: ${it.message}" }))
        if (r.errors.isEmpty()) stayCache.put(q, r)
        return r
    }

    suspend fun minutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, city: String): Int? {
        val key = "%.4f,%.4f>%.4f,%.4f".format(fromLat, fromLon, toLat, toLon)
        transitCache.get(key)?.let { return it }
        val res = guarded(transit.name) { transit.minutes(fromLat, fromLon, toLat, toLon, city) }.getOrNull()
        res?.let { transitCache.put(key, it) }
        return res ?: MockTransitProvider().minutes(fromLat, fromLon, toLat, toLon, city) // fallback estimate
    }

    suspend fun calendar(from: String, to: String, start: java.time.LocalDate, end: java.time.LocalDate): SearchResult<CalendarPrice> {
        val key = "$from$to$start$end"
        calendarCache.get(key)?.let { return it }
        val results = flights.map { p -> p.name to guarded(p.name) { p.calendar(from, to, start, end) } }
        val ok = results.flatMap { it.second.getOrNull().orEmpty() }
        val r = SearchResult(ok.groupBy { it.date }.map { (_, v) -> v.minBy { it.pricePerSeat } }.sortedBy { it.date },
            results.mapNotNull { (n, res) -> res.exceptionOrNull()?.takeIf { it !is ProviderUnavailable || ok.isEmpty() }?.let { "$n: ${it.message}" } })
        calendarCache.put(key, r)
        return r
    }

    suspend fun flightStatus(flightNo: String, date: java.time.LocalDate): Result<FlightStatus> = guarded(status.name) { status.status(flightNo, date) }
    suspend fun flightHistory(flightNo: String): Result<FlightHistory?> = guarded(status.name) { status.history(flightNo) }

    fun clearCaches() { flightCache.clear(); stayCache.clear(); calendarCache.clear() }

    companion object {
        fun mock(seed: SeedData = SeedData.default(), clock: Clock = Clock.systemUTC()) = ProviderHub(
            listOf(MockFlightProvider(seed, clock)), MockStayProvider(seed, clock), MockStatusProvider(seed, clock), MockTransitProvider(), clock,
        )
    }
}
