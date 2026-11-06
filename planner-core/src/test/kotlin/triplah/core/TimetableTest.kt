package triplah.core

import java.io.File
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimetableTest {
    private val root = File(System.getProperty("triplah.root") ?: "..")

    @Test
    fun `ics import expands weekly rules, applies EXDATE and RECURRENCE-ID, tags attendance`() {
        val t = IcsParser.parse("A", File(root, "seed/timetables/traveller-a.ics").readText(), "Asia/Singapore")
        val labs = t.events.filter { it.title.startsWith("SC2002 LAB") }
        assertTrue(labs.all { it.start.dayOfWeek == DayOfWeek.THURSDAY })
        assertTrue(labs.none { it.start.toLocalDate().toString() == "2026-09-24" }, "EXDATE removes recess week")
        assertTrue(labs.all { it.attendance == Attendance.MUST_ATTEND })
        assertTrue(t.events.filter { "LEC" in it.title }.all { it.attendance == Attendance.CAN_SKIP })
        // The moved tutorial replaces the 09:30 occurrence on 17 Nov.
        val tut17 = t.events.filter { it.title.startsWith("SC2005 TUT") && it.start.toLocalDate().toString() == "2026-11-17" }
        assertEquals(1, tut17.size); assertEquals(14, tut17.single().start.hour)
        // UTC times are converted to the timetable zone: 11:00Z = 19:00 SGT.
        val club = t.events.single { it.title.startsWith("Hackathon club") }
        assertEquals(19, club.start.hour); assertEquals(Attendance.CAN_SKIP, club.attendance)
        assertEquals(Attendance.MUST_ATTEND, t.events.single { "Quiz" in it.title }.attendance)
        // BYDAY=WE,FR gives two lectures a week.
        assertEquals(2, t.events.count { it.title.startsWith("MH1810") && it.start.toLocalDate() in java.time.LocalDate.parse("2026-11-16")..java.time.LocalDate.parse("2026-11-22") })
    }

    @Test
    fun `json import repeats weekly and defaults unknown events to must-attend`() {
        val t = JsonTimetableParser.parse("B", File(root, "seed/timetables/traveller-b.json").readText())
        assertEquals(4, t.events.count { it.title == "CS2040 Lab" })
        assertTrue(t.events.filter { it.title == "CS2040 Lecture" }.all { it.attendance == Attendance.CAN_SKIP })
        assertTrue(t.events.filter { it.title == "Part-time shift" }.all { it.attendance == Attendance.MUST_ATTEND })
    }

    @Test
    fun `keyword rules`() {
        val r = AttendanceRules(mapOf("^CS9999" to Attendance.CAN_SKIP))
        assertEquals(Attendance.MUST_ATTEND, r.tag("EE2001 Lab 3"))
        assertEquals(Attendance.MUST_ATTEND, r.tag("Final exam"))
        assertEquals(Attendance.CAN_SKIP, r.tag("EE2001 Lecture"))
        assertEquals(Attendance.MUST_ATTEND, r.tag("Collaboration label meeting"), "'label' is not 'lab' but unknown is must-attend")
        assertEquals(Attendance.CAN_SKIP, r.tag("CS9999 Lab"), "override wins")
        assertEquals(Attendance.CAN_SKIP, r.tag("Lab open day [skip]"))
    }

    @Test
    fun `bad input is rejected`() {
        assertFailsWith<ValidationException> { IcsParser.parse("A", "hello", "Asia/Singapore") }
        assertFailsWith<ValidationException> { JsonTimetableParser.parse("A", """{"events":[{"title":"x","start":"2026-11-10T10:00","end":"2026-11-10T09:00"}]}""") }
        assertFailsWith<ValidationException> { IcsParser.parse("A", "BEGIN:VCALENDAR\nEND:VCALENDAR", "Mars/Olympus") }
    }
}
