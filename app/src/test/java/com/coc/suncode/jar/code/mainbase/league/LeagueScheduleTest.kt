package com.coc.suncode.jar.code.mainbase.league

import com.coc.suncode.jar.code.clancapital.isWithinRaidWeekend
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeagueScheduleTest {
    private fun beijing(month: Int, day: Int, hour: Int, minute: Int = 0) =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(2026, month - 1, day, hour, minute)
        }

    @Test fun registrationStartsAndEndsAtFourPm() {
        assertFalse(isInsideLeagueWindow(beijing(11, 1, 15, 59), 0, 2))
        assertTrue(isInsideLeagueWindow(beijing(11, 1, 16), 0, 2))
        assertTrue(isInsideLeagueWindow(beijing(11, 3, 15, 59), 0, 2))
        assertFalse(isInsideLeagueWindow(beijing(11, 3, 16), 0, 2))
    }

    @Test fun extraLeagueSpansMonthEndAndRejectsUnknownIndex() {
        assertTrue(isInsideLeagueWindow(beijing(3, 1, 15, 59), 12, 9))
        assertFalse(isInsideLeagueWindow(beijing(2, 22, 17), 13, 9))
        assertFalse(isInsideLeagueWindow(beijing(2, 22, 17), 0, 9))
    }

    @Test fun raidWindowCoversFridayThroughMondayAfternoon() {
        assertFalse(isWithinRaidWeekend(beijing(11, 20, 15, 59)))
        assertTrue(isWithinRaidWeekend(beijing(11, 20, 16)))
        assertTrue(isWithinRaidWeekend(beijing(11, 23, 15, 59)))
        assertFalse(isWithinRaidWeekend(beijing(11, 23, 16)))
    }
}
