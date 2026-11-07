package nl.reijm.banklaunchtv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Watch time for one app (all its packages together), in milliseconds. */
class AppStats(
    val week: Long,
    val month: Long,
    val year: Long,
    /** Last 7 days, oldest first; the last entry is today. */
    val last7: LongArray,
)

/**
 * Reads how long apps were in front, from Android's usage records.
 *
 * Per-day totals are worked out from the usage events (exact, but Android only keeps those for
 * a limited time), and finished days are saved here so week/month/year stay complete later on.
 * For periods that started before this app was tracking, Android's own month/year totals are
 * used when they are larger.
 *
 * Needs the usage-access permission, granted once with:
 *   adb shell appops set nl.reijm.banklaunchtv GET_USAGE_STATS allow
 */
class UsageRepo(private val ctx: Context) {

    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val prefs = ctx.getSharedPreferences("usage_days", Context.MODE_PRIVATE)
    private val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun hasPermission(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun load(specs: List<AppSpec>): Map<String, AppStats> {
        val now = System.currentTimeMillis()
        val today = dayStart(now)

        val weekStart = cal(today).apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
            .let { if (it.timeInMillis > today) it.apply { add(Calendar.DAY_OF_MONTH, -7) } else it }.timeInMillis
        val monthStart = cal(today).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val yearStart = cal(today).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

        // Every day we need per-day totals for: this month and the last 7 days.
        val from = minOf(monthStart, weekStart, addDays(today, -6))
        val perDay = LinkedHashMap<Long, Map<String, Long>>()
        var d = from
        while (d <= today) {
            perDay[d] = dayTotals(d, now)
            d = addDays(d, 1)
        }
        // Earlier days of this year only exist in what we saved before.
        val savedYear = savedDays().filter { (day, _) -> day in yearStart until from }

        val monthly = systemTotals(UsageStatsManager.INTERVAL_MONTHLY, monthStart, now)
        val yearly = systemTotals(UsageStatsManager.INTERVAL_YEARLY, yearStart, now)
        val weekly = systemTotals(UsageStatsManager.INTERVAL_WEEKLY, weekStart, now)

        return specs.associate { spec ->
            fun sum(m: Map<String, Long>?) = spec.packages.sumOf { m?.get(it) ?: 0L }
            val week = perDay.filterKeys { it >= weekStart }.values.sumOf { sum(it) }
            val month = perDay.filterKeys { it >= monthStart }.values.sumOf { sum(it) }
            val year = perDay.filterKeys { it >= yearStart }.values.sumOf { sum(it) } + savedYear.values.sumOf { sum(it) }
            val last7 = LongArray(7) { i -> sum(perDay[addDays(today, i - 6)]) }
            spec.name to AppStats(
                week = maxOf(week, sum(weekly)),
                month = maxOf(month, sum(monthly)),
                year = maxOf(year, sum(yearly), month),
                last7 = last7,
            )
        }
    }

    // ------------------------------------------------------------------ per day

    /** Foreground time per package for the day starting at [start]. Finished days are cached. */
    private fun dayTotals(start: Long, now: Long): Map<String, Long> {
        val key = "d:" + keyFmt.format(start)
        val isToday = start == dayStart(now)
        if (!isToday) prefs.getString(key, null)?.let { return decode(it) }

        val end = if (isToday) now else addDays(start, 1)
        val computed = fromEvents(start, end) ?: return emptyMap()
        if (!isToday) prefs.edit().putString(key, encode(computed)).apply()
        return computed
    }

    /** Null when Android has no events at all for that range (too old): don't cache zeros then. */
    private fun fromEvents(start: Long, end: Long): Map<String, Long>? {
        val events = usm.queryEvents(start, end) ?: return null
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val seen = HashSet<String>()
        val total = HashMap<String, Long>()
        var any = false

        fun close(pkg: String, at: Long) {
            val since = open.remove(pkg) ?: return
            if (at > since) total[pkg] = (total[pkg] ?: 0L) + (at - since)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            any = true
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    seen += pkg
                    if (pkg !in open) open[pkg] = e.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // Paused without a resume today: it was already open at midnight.
                    if (pkg !in seen) { seen += pkg; open[pkg] = start }
                    close(pkg, e.timeStamp)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    open.keys.toList().forEach { close(it, e.timeStamp) }
            }
        }
        open.keys.toList().forEach { close(it, end) }
        return if (any) total else null
    }

    /** Android's own bucket totals, only from buckets that started inside the period. */
    private fun systemTotals(interval: Int, periodStart: Long, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        usm.queryUsageStats(interval, periodStart, now)
            ?.filter { it.firstTimeStamp >= periodStart - 6 * HOUR }
            ?.forEach { out[it.packageName] = (out[it.packageName] ?: 0L) + it.totalTimeInForeground }
        return out
    }

    private fun savedDays(): Map<Long, Map<String, Long>> =
        prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith("d:") || v !is String) return@mapNotNull null
            val day = runCatching { keyFmt.parse(k.substring(2))?.time }.getOrNull() ?: return@mapNotNull null
            day to decode(v)
        }.toMap()

    // ------------------------------------------------------------------ helpers

    private fun encode(m: Map<String, Long>) = m.entries.joinToString(";") { "${it.key}=${it.value}" }
    private fun decode(s: String): Map<String, Long> = s.split(';').mapNotNull {
        val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: 0L)
    }.toMap()

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t; firstDayOfWeek = Calendar.MONDAY }
    private fun dayStart(t: Long) = cal(t).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun addDays(t: Long, n: Int) = cal(t).apply { add(Calendar.DAY_OF_MONTH, n) }.timeInMillis

    companion object {
        private const val HOUR = 3_600_000L

        /** "6u 10m", "0u 50m"; three-digit hours drop the minutes: "148u". */
        fun format(ms: Long): String {
            val totalMin = ms / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h >= 100) "${h}u" else "${h}u ${m.toString().padStart(2, '0')}m"
        }
    }
}
