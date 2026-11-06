@file:UseSerializers(ZdtSerializer::class)

package triplah.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.ZonedDateTime

@Serializable
data class FreeWindow(
    /** Earliest time everyone can leave campus. */
    val start: ZonedDateTime,
    /** Latest time everyone must be back on campus. */
    val end: ZonedDateTime,
    val hours: Long,
    /** Can-skip classes inside the window. Free to miss, but listed. */
    val canSkipInside: List<SkippedClass> = emptyList(),
)

@Serializable
data class Conflict(
    val mustAttend: List<SkippedClass>,
    val canSkip: List<SkippedClass>,
) {
    val ok get() = mustAttend.isEmpty()
    val canSkipMinutes get() = canSkip.sumOf { it.minutesMissed }
}

object Windows {
    /**
     * Free windows shared by all travellers: the gaps between must-attend events of anyone,
     * clipped to [from, to], at least [minHours] long. Times are reported in [zone].
     */
    fun freeWindows(
        timetables: List<Timetable>,
        from: ZonedDateTime,
        to: ZonedDateTime,
        minHours: Long = 4,
        zone: java.time.ZoneId = from.zone,
    ): List<FreeWindow> {
        require(to.isAfter(from)) { "range end must be after start" }
        val busy = timetables.flatMap { tt -> tt.events.filter { it.attendance == Attendance.MUST_ATTEND } }
            .filter { it.overlaps(from, to) }
            .map { it.start.toInstant() to it.end.toInstant() }
            .sortedBy { it.first }
        // Merge overlapping busy intervals.
        val merged = mutableListOf<Pair<java.time.Instant, java.time.Instant>>()
        for (b in busy) {
            if (merged.isNotEmpty() && b.first <= merged.last().second) merged[merged.size - 1] = merged.last().first to maxOf(merged.last().second, b.second)
            else merged += b
        }
        val out = mutableListOf<FreeWindow>()
        var cursor = from.toInstant()
        for ((s, e) in merged + listOf(to.toInstant() to to.toInstant())) {
            if (s > cursor) {
                val ws = cursor.atZone(zone); val we = minOf(s, to.toInstant()).atZone(zone)
                val hours = Duration.between(ws, we).toHours()
                if (hours >= minHours) out += FreeWindow(ws, we, hours, check(timetables, ws, we).canSkip)
            }
            cursor = maxOf(cursor, e)
        }
        return out
    }

    /** What a traveller misses by being away from campus between [leave] and [back]. */
    fun check(timetables: List<Timetable>, leave: ZonedDateTime, back: ZonedDateTime): Conflict {
        val must = mutableListOf<SkippedClass>(); val skip = mutableListOf<SkippedClass>()
        for (tt in timetables) for (e in tt.events) {
            val m = e.overlapMinutes(leave, back)
            if (m <= 0) continue
            val s = SkippedClass(tt.label, e.title, e.start, m)
            if (e.attendance == Attendance.MUST_ATTEND) must += s else skip += s
        }
        return Conflict(must, skip)
    }
}
