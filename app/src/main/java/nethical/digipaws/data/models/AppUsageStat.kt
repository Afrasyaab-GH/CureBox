package nethical.digipaws.data.models

import java.time.ZonedDateTime

data class AppUsageStat(
    val packageName: String,
    val totalTime: Long,
    val startTimes: List<ZonedDateTime>
)
