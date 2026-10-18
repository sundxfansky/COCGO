package com.coc.suncode.core.qq

/** Round an active grant up to the next minute without losing day and hour boundaries. */
internal fun formatQqAuthorizationTime(remainingMillis: Long): String {
    val minutes = if (remainingMillis <= 0L) 0L else remainingMillis / 60_000L + if (remainingMillis % 60_000L > 0L) 1L else 0L
    val days = minutes / 1440L
    val hours = minutes % 1440L / 60L
    return buildString {
        if (days > 0L) append("${days}天 ")
        if (days > 0L || hours > 0L) append("${hours}小时 ")
        append("${minutes % 60L}分钟")
    }
}
