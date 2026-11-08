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
    /** This week Monday..Sunday; days still to come are -1. */
    val weekDays: LongArray,
)

/** Average watch time per day over one week or month; null when Android has no record of it. */
class PeriodAverage(val start: Long, val perDay: Long?, val total: Long = 0L, val days: Int = 1)

/** History for the analysis screen, for the apps in the list together. */
class History(
    /** The last 8 weeks, oldest first; the last one is this week so far. */
    val weeks: List<PeriodAverage>,
    /** January up to this month, oldest first; the last one is this month so far. */
    val months: List<PeriodAverage>,
)

/**
 * Reads how long apps were in front, from Android's usage records.
 *
 * Per-day totals are worked out from the usage events (exact, but Android only keeps those for
 * a limited time), and finished days are saved here so week/month/year stay complete later on.
 * For periods that started before this app was tracking, Android's own month/year totals are
 * used when they are larger. Android's "yearly" record doesn't follow the calendar (on this TV
 * it runs from 2 November), so for the year we take the part of each record that falls in it.
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
        // Saved days from an older (wrong) way of counting are thrown away and recounted.
        if (prefs.getInt("format", 1) != FORMAT) prefs.edit().clear().putInt("format", FORMAT).apply()
        val now = System.currentTimeMillis()
        val today = dayStart(now)

        val weekStart = cal(today).apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
            .let { if (it.timeInMillis > today) it.apply { add(Calendar.DAY_OF_MONTH, -7) } else it }.timeInMillis
        val monthStart = cal(today).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val yearStart = cal(today).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

        // Every day we need per-day totals for: this month and this week.
        val from = minOf(monthStart, weekStart)
        val perDay = LinkedHashMap<Long, Map<String, Long>>()
        var d = from
        while (d <= today) {
            perDay[d] = dayTotals(d, now)
            d = addDays(d, 1)
        }
        // Earlier days of this year only exist in what we saved before.
        val savedYear = savedDays().filter { (day, _) -> day in yearStart until from }

        val monthly = systemTotals(UsageStatsManager.INTERVAL_MONTHLY, monthStart, now)
        val yearly = overlapTotals(UsageStatsManager.INTERVAL_YEARLY, yearStart, now)
        val weekly = systemTotals(UsageStatsManager.INTERVAL_WEEKLY, weekStart, now)

        return specs.associate { spec ->
            fun sum(m: Map<String, Long>?) = spec.packages.sumOf { m?.get(it) ?: 0L }
            val week = perDay.filterKeys { it >= weekStart }.values.sumOf { sum(it) }
            val month = perDay.filterKeys { it >= monthStart }.values.sumOf { sum(it) }
            val year = perDay.filterKeys { it >= yearStart }.values.sumOf { sum(it) } + savedYear.values.sumOf { sum(it) }
            val weekDays = LongArray(7) { i ->
                val day = addDays(weekStart, i)
                if (day > today) -1L else sum(perDay[day])
            }
            spec.name to AppStats(
                week = maxOf(week, sum(weekly)),
                month = maxOf(month, sum(monthly)),
                year = maxOf(year, sum(yearly), month),
                weekDays = weekDays,
            )
        }
    }

    /**
     * Average per day for recent weeks and months. Uses our saved days where we have them and
     * Android's weekly/monthly records otherwise (those don't line up with calendar weeks, so
     * that part is an estimate). Android keeps weekly records for about a month.
     */
    fun history(specs: List<AppSpec>): History {
        val pkgs = specs.flatMap { it.packages }.toSet()
        val now = System.currentTimeMillis()
        val today = dayStart(now)
        val thisWeek = cal(today).apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
            .let { if (it.timeInMillis > today) it.apply { add(Calendar.DAY_OF_MONTH, -7) } else it }.timeInMillis
        val thisMonth = cal(today).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

        fun average(start: Long, end: Long, interval: Int): PeriodAverage {
            val stop = minOf(end, now)
            var fromDays = 0L
            var days = 0
            var daysWithData = 0
            var d = start
            while (d < stop) {
                val all = dayTotals(d, now)
                if (all.isNotEmpty()) daysWithData++
                fromDays += all.filterKeys { it in pkgs }.values.sum()
                days++
                d = addDays(d, 1)
            }
            // Android's records must reach back to the start of the period; a record that only
            // covers its last days would make the whole period look far too quiet.
            val recordsFrom = usm.queryUsageStats(interval, start, stop)
                ?.filter { it.lastTimeStamp > start }?.minOfOrNull { it.firstTimeStamp }
            val covered = stop == now || daysWithData >= minOf(days, days - 2) ||
                (recordsFrom != null && recordsFrom <= addDays(start, 1))
            val estimate = overlapTotals(interval, start, stop).filterKeys { it in pkgs }.values.sum()
            val total = maxOf(fromDays, estimate)
            days = days.coerceAtLeast(1)
            return PeriodAverage(start, if (total > 0 && covered) total / days else null, if (covered) total else 0L, days)
        }

        val weeks = (7 downTo 0).map { back ->
            val start = addDays(thisWeek, -7 * back)
            average(start, addDays(start, 7), UsageStatsManager.INTERVAL_WEEKLY)
        }
        val months = (cal(today).get(Calendar.MONTH) downTo 0).map { back ->
            val start = cal(thisMonth).apply { add(Calendar.MONTH, -back) }.timeInMillis
            val end = cal(start).apply { add(Calendar.MONTH, 1) }.timeInMillis
            average(start, end, UsageStatsManager.INTERVAL_MONTHLY)
        }
        return History(weeks, months)
    }

    /** Every app's time this year, most watched first (for finding apps missing from the list). */
    fun topApps(): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        val yearStart = cal(dayStart(now)).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        return overlapTotals(UsageStatsManager.INTERVAL_YEARLY, yearStart, now)
            .toList().sortedByDescending { it.second }
    }

    // ------------------------------------------------------------------ per day

    /** Foreground time per package for the day starting at [start]. Finished days are cached. */
    private fun dayTotals(start: Long, now: Long): Map<String, Long> {
        val key = "d:" + keyFmt.format(start)
        val isToday = start == dayStart(now)
        if (!isToday) prefs.getString(key, null)?.let { return decode(it) }

        val end = if (isToday) now else addDays(start, 1)
        val computed = fromEvents(start, end) ?: run {
            // Older than Android keeps events for: remember that there's nothing, so we don't ask again.
            if (now - start > 21 * 24 * HOUR) prefs.edit().putString(key, "").apply()
            return emptyMap()
        }
        if (!isToday) prefs.edit().putString(key, encode(computed)).apply()
        return computed
    }

    /**
     * Null when Android has no events at all for that range (too old): don't cache zeros then.
     *
     * An app counts as in front while any of its screens (activities) is resumed. Apps like
     * YouTube run more than one screen, so a single screen pausing doesn't stop the clock.
     */
    private fun fromEvents(start: Long, end: Long): Map<String, Long>? {
        val events = usm.queryEvents(start, end) ?: return null
        val e = UsageEvents.Event()
        val resumed = HashMap<String, HashSet<String>>()   // package -> its resumed screens
        val since = HashMap<String, Long>()                 // package -> in front since
        val seen = HashSet<String>()                        // "pkg/class" seen today
        val total = HashMap<String, Long>()
        var any = false

        fun stop(pkg: String, at: Long) {
            val from = since.remove(pkg) ?: return
            if (at > from) total[pkg] = (total[pkg] ?: 0L) + (at - from)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            any = true
            val pkg = e.packageName ?: continue
            val screen = pkg + "/" + (e.className ?: "")
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    seen += screen
                    val set = resumed.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty() && pkg !in since) since[pkg] = e.timeStamp
                    set += screen
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // Paused without a resume today: it was already open at midnight.
                    if (screen !in seen) {
                        seen += screen
                        if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && pkg !in since) since[pkg] = start
                    }
                    val set = resumed[pkg]
                    set?.remove(screen)
                    if (set.isNullOrEmpty()) stop(pkg, e.timeStamp)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    since.keys.toList().forEach { stop(it, e.timeStamp) }
                    resumed.clear()
                }
            }
        }
        since.keys.toList().forEach { stop(it, end) }
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

    /**
     * Android's bucket totals, counting each bucket for the share of its time span that falls
     * inside [periodStart, now]. Assumes watching was spread evenly over the bucket: an estimate.
     */
    private fun overlapTotals(interval: Int, periodStart: Long, now: Long): Map<String, Long> {
        // [now] is the end of the period (usually the current time).
        val out = HashMap<String, Long>()
        usm.queryUsageStats(interval, periodStart, now)?.forEach { u ->
            val span = u.lastTimeStamp - u.firstTimeStamp
            if (span <= 0 || u.totalTimeInForeground <= 0) return@forEach
            val inside = minOf(u.lastTimeStamp, now) - maxOf(u.firstTimeStamp, periodStart)
            if (inside <= 0) return@forEach
            val part = (u.totalTimeInForeground * (inside.toDouble() / span)).toLong()
            out[u.packageName] = (out[u.packageName] ?: 0L) + part
        }
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
        /** Raise when the way days are counted changes, so saved days are recounted. */
        private const val FORMAT = 2

        /** "6h 10m", "0h 50m"; from 10 hours on, whole hours only: 43h 35m shows as "44h". */
        fun format(ms: Long): String {
            val totalMin = ms / 60_000
            if (totalMin >= 10 * 60) return "${(totalMin + 30) / 60}h"
            return "${totalMin / 60}h ${(totalMin % 60).toString().padStart(2, '0')}m"
        }
    }
}
