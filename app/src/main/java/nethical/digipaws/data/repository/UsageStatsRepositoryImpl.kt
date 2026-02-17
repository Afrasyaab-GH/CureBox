package nethical.digipaws.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nethical.digipaws.data.models.AppUsageStat
import nethical.digipaws.utils.UsageStatsHelper
import java.time.LocalDate

class UsageStatsRepositoryImpl(context: Context) : UsageStatsRepository {

    private val usageStatsHelper = UsageStatsHelper(context)

    override suspend fun getStatsByTimestamps(start: Long, end: Long): Result<List<AppUsageStat>> =
        withContext(Dispatchers.IO) {
            runCatching {
                usageStatsHelper.getForegroundStatsByTimestamps(start, end)
            }
        }

    override suspend fun getStatsByRelativeDay(offset: Int): Result<List<AppUsageStat>> =
        withContext(Dispatchers.IO) {
            runCatching {
                usageStatsHelper.getForegroundStatsByRelativeDay(offset)
            }
        }

    override suspend fun getStatsByDay(date: LocalDate): Result<List<AppUsageStat>> =
        withContext(Dispatchers.IO) {
            runCatching {
                usageStatsHelper.getForegroundStatsByDay(date)
            }
        }
}
