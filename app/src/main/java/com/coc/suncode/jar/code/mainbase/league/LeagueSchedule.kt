package com.coc.suncode.jar.code.mainbase.league

import com.coc.suncode.jar.code.universal.smalltools.getConfigRuntime
import com.coc.suncode.jar.ui.schema.Schema
import java.util.Calendar
import java.util.TimeZone

internal fun isInClanLeaguePeriod() = isInsideLeagueWindow(currentBeijingTime(), extraLeagueIndex(), 9)

internal fun isInLeagueRegistrationPeriod() = isInsideLeagueWindow(currentBeijingTime(), extraLeagueIndex(), 2)

private fun extraLeagueIndex() =
    getConfigRuntime(Schema.MAIN_BASE_SETTINGS.EVENT_LEAGUE_START_DAY.key).toIntOrNull() ?: 0

private fun currentBeijingTime() = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))

/** Checks both months because event leagues can extend across the end of February. */
internal fun isInsideLeagueWindow(now: Calendar, extraIndex: Int, days: Int): Boolean {
    fun includes(startDay: Int, monthOffset: Int): Boolean {
        val start = now.clone() as Calendar
        start.set(Calendar.DAY_OF_MONTH, 1)
        start.add(Calendar.MONTH, monthOffset)
        start.set(Calendar.DAY_OF_MONTH, startDay)
        start.set(Calendar.HOUR_OF_DAY, 16)
        start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)
        val end = start.clone() as Calendar
        end.add(Calendar.DAY_OF_MONTH, days)
        return !now.before(start) && now.before(end)
    }
    return includes(1, 0) ||
        (extraIndex in 1..12 && (includes(extraIndex + 10, 0) || includes(extraIndex + 10, -1)))
}
