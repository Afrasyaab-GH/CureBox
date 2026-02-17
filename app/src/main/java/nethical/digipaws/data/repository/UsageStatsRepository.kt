package nethical.digipaws.data.repository

import nethical.digipaws.data.models.AppUsageStat
import java.time.LocalDate

interface UsageStatsRepository {

    suspend fun getStatsByTimestamps(start: Long, end: Long): Result<List<AppUsageStat>>

    suspend fun getStatsByRelativeDay(offset: Int): Result<List<AppUsageStat>>

    suspend fun getStatsByDay(date: LocalDate): Result<List<AppUsageStat>>
}
