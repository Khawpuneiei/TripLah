package triplah.api

import triplah.providers.FlightProvider
import triplah.providers.HttpGateway
import triplah.providers.MockFlightProvider
import triplah.providers.MockStatusProvider
import triplah.providers.MockStayProvider
import triplah.providers.MockTransitProvider
import triplah.providers.ProviderHub
import triplah.providers.QuotaGuard
import triplah.providers.SeedData
import triplah.providers.StatusProvider
import triplah.providers.StayProvider
import triplah.providers.Store
import triplah.providers.TransitProvider
import triplah.providers.real.AeroDataBoxStatus
import triplah.providers.real.LiteApiStays
import triplah.providers.real.OneMapTransit
import triplah.providers.real.SerpApiFlights
import triplah.providers.real.TravelpayoutsCalendar

/**
 * Picks providers from environment variables. Secrets only ever come from the environment.
 * TRIPLAH_PROVIDERS=mock (default) uses seed data only. TRIPLAH_PROVIDERS=real uses each real
 * adapter whose key is set, and the mock for the rest.
 */
object Wiring {
    /** Free-tier sizes per month (third-party figures; confirm at sign-up). Calls stop at 90%. */
    val FREE_TIER = mapOf("serpapi" to 250, "travelpayouts" to 3000, "liteapi" to 1000, "aerodatabox" to 300, "onemap" to 5000)

    fun fromEnv(env: Map<String, String> = System.getenv()): TripService {
        val seed = SeedData.default()
        val store = Store(env["TRIPLAH_DB"] ?: "triplah.db")
        val real = env["TRIPLAH_PROVIDERS"] == "real"
        // Live SerpApi searches can take 5-10 s on a cold start, so real providers get 15 s (seed-data mocks keep 5 s).
        val timeout = if (real) java.time.Duration.ofSeconds(15) else java.time.Duration.ofSeconds(5)
        val gw = HttpGateway(HttpGateway.DEFAULT_HOSTS, QuotaGuard(FREE_TIER, store), timeout)
        val flights = buildList<FlightProvider> {
            if (real && env["SERPAPI_KEY"] != null) add(SerpApiFlights(gw, env.getValue("SERPAPI_KEY")))
            if (real && env["TRAVELPAYOUTS_TOKEN"] != null) add(TravelpayoutsCalendar(gw, env.getValue("TRAVELPAYOUTS_TOKEN")))
            if (none { it !is TravelpayoutsCalendar }) add(MockFlightProvider(seed))
        }
        val stays: StayProvider = if (real && env["LITEAPI_KEY"] != null) LiteApiStays(gw, env.getValue("LITEAPI_KEY")) else MockStayProvider(seed)
        val status: StatusProvider = if (real && env["AERODATABOX_KEY"] != null) AeroDataBoxStatus(gw, env.getValue("AERODATABOX_KEY")) else MockStatusProvider(seed)
        val transit: TransitProvider = if (real && env["ONEMAP_TOKEN"] != null) OneMapTransit(gw, env.getValue("ONEMAP_TOKEN")) else MockTransitProvider()
        return TripService(ProviderHub(flights, stays, status, transit, timeoutMs = timeout.toMillis()), store, seed)
    }

    fun mock(store: Store = Store.memory(), clock: java.time.Clock = java.time.Clock.systemUTC()): TripService =
        TripService(ProviderHub.mock(SeedData.default(), clock), store)
}
