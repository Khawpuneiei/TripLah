@file:UseSerializers(ZdtSerializer::class, LocalDateSerializer::class)

package triplah.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Tags events as must-attend or can-skip.
 * Order: explicit "[must]" / "[skip]" in the title, user overrides (regex on the title), keyword rules.
 * Anything not recognised is must-attend: the safe default.
 */
class AttendanceRules(
    private val overrides: Map<String, Attendance> = emptyMap(),
) {
    private val must = Regex("""\b(lab|labs|exam|exams|quiz|test|midterm|mid-term|final|finals|presentation|viva|tutorial|tut|seminar|practical)\b""", RegexOption.IGNORE_CASE)
    private val skip = Regex("""\b(lecture|lec|lect|webcast|recorded|optional|office hours?)\b""", RegexOption.IGNORE_CASE)

    fun tag(title: String, categories: String = ""): Attendance {
        val t = title.lowercase()
        if ("[must]" in t) return Attendance.MUST_ATTEND
        if ("[skip]" in t) return Attendance.CAN_SKIP
        overrides.forEach { (pattern, a) -> if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(title)) return a }
        val text = "$title $categories"
        if (must.containsMatchIn(text)) return Attendance.MUST_ATTEND
        if (skip.containsMatchIn(text)) return Attendance.CAN_SKIP
        return Attendance.MUST_ATTEND
    }
}

object IcsParser {
    private val windowsZones = mapOf(
        "singapore standard time" to "Asia/Singapore",
        "malay peninsula standard time" to "Asia/Singapore",
        "se asia standard time" to "Asia/Bangkok",
        "china standard time" to "Asia/Shanghai",
        "taipei standard time" to "Asia/Taipei",
        "tokyo standard time" to "Asia/Tokyo",
        "korea standard time" to "Asia/Seoul",
        "utc" to "UTC",
    )

    private const val MAX_OCCURRENCES = 2000

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    fun parse(
        travellerId: String,
        text: String,
        defaultZone: String,
        rules: AttendanceRules = AttendanceRules(),
        horizonEnd: ZonedDateTime? = null,
    ): Timetable {
        val zone = resolveZone(defaultZone)
        val lines = unfold(text)
        if (lines.none { it.equals("BEGIN:VCALENDAR", ignoreCase = true) }) throw ValidationException("not an .ics calendar (no BEGIN:VCALENDAR)")
        val vevents = mutableListOf<List<Prop>>()
        var cur: MutableList<Prop>? = null
        var depth = 0
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", true) -> { cur = mutableListOf(); depth = 0 }
                line.equals("END:VEVENT", true) -> { cur?.let { vevents += it }; cur = null }
                line.startsWith("BEGIN:", true) && cur != null -> depth++
                line.startsWith("END:", true) && cur != null -> depth--
                cur != null && depth == 0 -> parseProp(line)?.let { cur.add(it) }
            }
        }

        data class Raw(val uid: String, val recurrenceId: ZonedDateTime?, val events: List<Event>)
        val raws = vevents.mapNotNull { props ->
            val p = props.associateBy { it.name }
            if (p["STATUS"]?.value.equals("CANCELLED", true)) return@mapNotNull null
            val dtStart = p["DTSTART"] ?: return@mapNotNull null
            val start = parseDate(dtStart, zone)
            val end = p["DTEND"]?.let { parseDate(it, zone) }
                ?: p["DURATION"]?.let { start.plus(parseDuration(it.value)) }
                ?: if (dtStart.params["VALUE"] == "DATE") start.plusDays(1) else start.plusHours(1)
            val title = unescape(p["SUMMARY"]?.value ?: "(untitled)")
            val cats = props.filter { it.name == "CATEGORIES" }.joinToString(" ") { it.value }
            val location = p["LOCATION"]?.value?.let(::unescape)
            val attendance = rules.tag(title, cats)
            val exdates = props.filter { it.name == "EXDATE" }.flatMap { prop ->
                prop.value.split(',').map { parseDate(Prop("EXDATE", prop.params, it), zone).toInstant() }
            }.toSet()
            val starts = p["RRULE"]?.let { expand(start, it.value, zone, horizonEnd) } ?: listOf(start)
            val length = Duration.between(start, end)
            val events = starts.filter { it.toInstant() !in exdates }
                .map { Event(title, it, it.plus(length), attendance, location) }
            Raw(p["UID"]?.value ?: title, p["RECURRENCE-ID"]?.let { parseDate(it, zone) }, events)
        }
        // A RECURRENCE-ID event replaces one occurrence of the series with the same UID.
        val replaced = raws.filter { it.recurrenceId != null }.map { it.uid to it.recurrenceId!!.toInstant() }.toSet()
        val events = raws.flatMap { raw ->
            if (raw.recurrenceId != null) raw.events
            else raw.events.filter { (raw.uid to it.start.toInstant()) !in replaced }
        }.sortedBy { it.start.toInstant() }
        return Timetable(travellerId, zone.id, events)
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1)
            else if (raw.isNotBlank()) out += raw.trimEnd()
        }
        return out
    }

    private fun parseProp(line: String): Prop? {
        // NAME;PARAM=V;PARAM="V:x":VALUE. The value starts at the first ':' outside quotes.
        var inQuote = false
        var idx = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') inQuote = !inQuote
            if (c == ':' && !inQuote) { idx = i; break }
        }
        if (idx < 0) return null
        val head = line.substring(0, idx).split(';')
        val params = head.drop(1).mapNotNull {
            val eq = it.indexOf('='); if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(idx + 1))
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    fun resolveZone(id: String?): ZoneId {
        if (id.isNullOrBlank()) return ZoneId.of("Asia/Singapore")
        windowsZones[id.lowercase()]?.let { return ZoneId.of(it) }
        return try { ZoneId.of(id) } catch (e: Exception) { throw ValidationException("unknown time zone '$id'") }
    }

    private val dt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val d = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun parseDate(p: Prop, defaultZone: ZoneId): ZonedDateTime {
        val v = p.value.trim()
        return try {
            when {
                p.params["VALUE"] == "DATE" || v.length == 8 -> LocalDate.parse(v, d).atStartOfDay(p.params["TZID"]?.let(::resolveZone) ?: defaultZone)
                v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), dt).atZone(ZoneOffset.UTC).withZoneSameInstant(defaultZone)
                else -> LocalDateTime.parse(v, dt).atZone(p.params["TZID"]?.let(::resolveZone) ?: defaultZone)
            }
        } catch (e: ValidationException) { throw e } catch (e: Exception) {
            throw ValidationException("bad date '${p.value}' in ${p.name}")
        }
    }

    private fun parseDuration(v: String): Duration {
        val weeks = Regex("""^([+-])?P(\d+)W$""").find(v)
        if (weeks != null) return Duration.ofDays(7L * weeks.groupValues[2].toLong())
        return Duration.parse(v.replace(Regex("""^\+"""), ""))
    }

    private val days = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    /** Supports FREQ=DAILY|WEEKLY with INTERVAL, COUNT, UNTIL and BYDAY. That covers university and Google exports. */
    private fun expand(start: ZonedDateTime, rrule: String, zone: ZoneId, horizonEnd: ZonedDateTime?): List<ZonedDateTime> {
        val r = rrule.split(';').mapNotNull { val eq = it.indexOf('='); if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1) }.toMap()
        val freq = r["FREQ"]?.uppercase() ?: return listOf(start)
        if (freq != "WEEKLY" && freq != "DAILY") throw ValidationException("RRULE FREQ=$freq is not supported (only DAILY and WEEKLY)")
        val interval = r["INTERVAL"]?.toIntOrNull() ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { parseDate(Prop("UNTIL", emptyMap(), it), start.zone) }
        val limit = horizonEnd ?: start.plusYears(1)
        val byDay = r["BYDAY"]?.split(',')?.mapNotNull { days[it.takeLast(2).uppercase()] }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: setOf(start.dayOfWeek)
        val out = mutableListOf<ZonedDateTime>()
        var day = start.toLocalDate()
        val weekStart = start.toLocalDate().with(DayOfWeek.MONDAY)
        while (out.size < MAX_OCCURRENCES) {
            val candidate = day.atTime(start.toLocalTime()).atZone(start.zone)
            if (until != null && candidate.isAfter(until)) break
            if (candidate.isAfter(limit)) break
            if (count != null && out.size >= count) break
            val include = when (freq) {
                "DAILY" -> java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), day) % interval == 0L
                else -> day.dayOfWeek in byDay &&
                    java.time.temporal.ChronoUnit.WEEKS.between(weekStart, day.with(DayOfWeek.MONDAY)) % interval == 0L
            }
            if (include && !candidate.isBefore(start)) out += candidate
            day = day.plusDays(1)
        }
        return out
    }
}

/** JSON timetable format, for people without an .ics export. */
@Serializable
data class JsonTimetable(
    val zone: String = "Asia/Singapore",
    val events: List<JsonEvent>,
)

@Serializable
data class JsonEvent(
    val title: String,
    /** Local time, e.g. "2026-11-20T09:30". The timetable zone is applied. An explicit offset is also accepted. */
    val start: String,
    val end: String,
    val attendance: Attendance? = null,
    val location: String? = null,
    /** Repeat every week on the same weekday until this date (inclusive). */
    val repeatWeeklyUntil: LocalDate? = null,
)

object JsonTimetableParser {
    fun parse(travellerId: String, text: String, rules: AttendanceRules = AttendanceRules()): Timetable {
        val jt = try { TripJson.decodeFromString(JsonTimetable.serializer(), text) } catch (e: ValidationException) { throw e } catch (e: Exception) {
            throw ValidationException("timetable JSON is invalid: ${e.message?.take(120)}")
        }
        val zone = IcsParser.resolveZone(jt.zone)
        fun at(s: String): ZonedDateTime = try {
            if (Regex("""[+-]\d\d:\d\d|Z""").containsMatchIn(s.substringAfter('T', ""))) parseZdt(s).withZoneSameInstant(zone)
            else LocalDateTime.parse(s).atZone(zone)
        } catch (e: ValidationException) { throw e } catch (e: Exception) { throw ValidationException("bad time '$s'") }
        val events = jt.events.flatMap { e ->
            val s = at(e.start); val en = at(e.end)
            if (!en.isAfter(s)) throw ValidationException("event '${e.title}' ends before it starts")
            val a = e.attendance ?: rules.tag(e.title)
            val starts = generateSequence(s) { it.plusWeeks(1) }
                .takeWhile { e.repeatWeeklyUntil == null || !it.toLocalDate().isAfter(e.repeatWeeklyUntil) }
                .take(if (e.repeatWeeklyUntil == null) 1 else 60).toList()
            starts.map { st -> Event(e.title, st, st.plus(Duration.between(s, en)), a, e.location) }
        }.sortedBy { it.start.toInstant() }
        return Timetable(travellerId, zone.id, events)
    }
}
