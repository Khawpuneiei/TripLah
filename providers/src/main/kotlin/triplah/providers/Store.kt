@file:UseSerializers(ZdtSerializer::class, LocalDateSerializer::class)

package triplah.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import triplah.core.LocalDateSerializer
import triplah.core.Timetable
import triplah.core.TripJson
import triplah.core.WatchedFlight
import triplah.core.ZdtSerializer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

@Serializable
data class PriceWatch(
    val id: Long = 0,
    val from: String,
    val to: String,
    val date: LocalDate,
    val pax: Int,
    val targetPrice: Double,
    val bookBy: LocalDate,
    val lastPrice: Double? = null,
    val lastChecked: String? = null,
    val status: String = "ACTIVE",
)

@Serializable
data class FlightWatch(
    val id: Long = 0,
    val flight: WatchedFlight,
    val nextCheck: ZonedDateTime,
    val lastState: String? = null,
    val lastMessage: String? = null,
)

@Serializable
data class Alert(val id: Long, val at: String, val kind: String, val message: String)

/**
 * Local SQLite store: timetables, watches, price history, flight history, quota counts, alerts.
 * Timetables stay on this machine. Travellers are "Traveller A / B": no names, no ID numbers.
 */
class Store(path: String = System.getenv("TRIPLAH_DB") ?: "triplah.db") : QuotaStore, AutoCloseable {
    companion object { fun memory() = Store(":memory:") }

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            listOf(
                "CREATE TABLE IF NOT EXISTS timetable (traveller TEXT PRIMARY KEY, json TEXT NOT NULL, imported_at TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS quota (provider TEXT, month TEXT, used INTEGER NOT NULL, PRIMARY KEY (provider, month))",
                "CREATE TABLE IF NOT EXISTS price_watch (id INTEGER PRIMARY KEY AUTOINCREMENT, src TEXT, dst TEXT, date TEXT, pax INTEGER, target REAL, book_by TEXT, last_price REAL, last_checked TEXT, status TEXT)",
                "CREATE TABLE IF NOT EXISTS price_history (src TEXT, dst TEXT, date TEXT, fetched_at TEXT, price REAL)",
                "CREATE TABLE IF NOT EXISTS flight_watch (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, next_check TEXT NOT NULL, last_state TEXT, last_message TEXT, active INTEGER DEFAULT 1)",
                "CREATE TABLE IF NOT EXISTS flight_history (flight_no TEXT, date TEXT, state TEXT, delay_min INTEGER, recorded_at TEXT)",
                "CREATE TABLE IF NOT EXISTS alert (id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, kind TEXT, message TEXT)",
            ).forEach(st::executeUpdate)
        }
    }

    private inline fun <T> q(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = synchronized(conn) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun exec(sql: String, vararg args: Any?): Long = synchronized(conn) {
        conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
            ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
        }
    }

    // timetables
    fun saveTimetable(t: Timetable) = exec("INSERT OR REPLACE INTO timetable VALUES (?,?,?)", t.travellerId, TripJson.encodeToString(Timetable.serializer(), t), Instant.now().toString())
    fun timetable(id: String): Timetable? = q("SELECT json FROM timetable WHERE traveller=?", id) { TripJson.decodeFromString(Timetable.serializer(), it.getString(1)) }.firstOrNull()
    fun timetables(): Map<String, Timetable> = q("SELECT json FROM timetable") { TripJson.decodeFromString(Timetable.serializer(), it.getString(1)) }.associateBy { it.travellerId }

    // quota
    override fun used(provider: String, month: String) = q("SELECT used FROM quota WHERE provider=? AND month=?", provider, month) { it.getInt(1) }.firstOrNull() ?: 0
    override fun add(provider: String, month: String) { exec("INSERT INTO quota VALUES (?,?,1) ON CONFLICT(provider, month) DO UPDATE SET used = used + 1", provider, month) }

    // price watch
    fun addPriceWatch(w: PriceWatch): Long = exec("INSERT INTO price_watch (src,dst,date,pax,target,book_by,status) VALUES (?,?,?,?,?,?,?)", w.from, w.to, w.date.toString(), w.pax, w.targetPrice, w.bookBy.toString(), w.status)
    fun priceWatches(activeOnly: Boolean = true): List<PriceWatch> =
        q("SELECT id,src,dst,date,pax,target,book_by,last_price,last_checked,status FROM price_watch" + if (activeOnly) " WHERE status='ACTIVE'" else "") {
            PriceWatch(it.getLong(1), it.getString(2), it.getString(3), LocalDate.parse(it.getString(4)), it.getInt(5), it.getDouble(6), LocalDate.parse(it.getString(7)),
                it.getDouble(8).takeUnless { _ -> it.wasNull() }, it.getString(9), it.getString(10))
        }
    fun updatePriceWatch(id: Long, lastPrice: Double, checkedAt: String, status: String) { exec("UPDATE price_watch SET last_price=?, last_checked=?, status=? WHERE id=?", lastPrice, checkedAt, status, id) }

    // price history
    fun recordPrice(from: String, to: String, date: LocalDate, price: Double, at: Instant) { exec("INSERT INTO price_history VALUES (?,?,?,?,?)", from, to, date.toString(), at.toString(), price) }
    fun routePrices(from: String, to: String, sinceDays: Long = 90): List<Double> =
        q("SELECT price FROM price_history WHERE src=? AND dst=? AND fetched_at >= ?", from, to, Instant.now().minusSeconds(sinceDays * 86400).toString()) { it.getDouble(1) }

    // flight watch
    fun addFlightWatch(f: WatchedFlight, nextCheck: ZonedDateTime): Long = exec("INSERT INTO flight_watch (json, next_check) VALUES (?,?)", TripJson.encodeToString(WatchedFlight.serializer(), f), nextCheck.toInstant().toString())
    fun flightWatches(): List<FlightWatch> = q("SELECT id,json,next_check,last_state,last_message FROM flight_watch WHERE active=1") {
        val f = TripJson.decodeFromString(WatchedFlight.serializer(), it.getString(2))
        FlightWatch(it.getLong(1), f, Instant.parse(it.getString(3)).atZone(f.depart.zone), it.getString(4), it.getString(5))
    }
    fun updateFlightWatch(id: Long, nextCheck: ZonedDateTime?, state: String, message: String) {
        if (nextCheck == null) exec("UPDATE flight_watch SET active=0, last_state=?, last_message=? WHERE id=?", state, message, id)
        else exec("UPDATE flight_watch SET next_check=?, last_state=?, last_message=? WHERE id=?", nextCheck.toInstant().toString(), state, message, id)
    }

    // flight history (the risk signal improves as the watcher collects data)
    fun recordFlight(flightNo: String, date: LocalDate, state: String, delayMin: Long) { exec("INSERT INTO flight_history VALUES (?,?,?,?,?)", flightNo, date.toString(), state, delayMin, Instant.now().toString()) }
    fun flightRecords(flightNo: String): List<Triple<String, String, Long>> = q("SELECT date, state, delay_min FROM flight_history WHERE flight_no=?", flightNo) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }

    // alerts
    fun addAlert(kind: String, message: String): Long = exec("INSERT INTO alert (at, kind, message) VALUES (?,?,?)", Instant.now().toString(), kind, message)
    fun alerts(): List<Alert> = q("SELECT id, at, kind, message FROM alert ORDER BY id") { Alert(it.getLong(1), it.getString(2), it.getString(3), it.getString(4)) }

    override fun close() = conn.close()
}
