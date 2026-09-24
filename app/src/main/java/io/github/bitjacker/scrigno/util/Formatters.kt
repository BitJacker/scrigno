package io.github.bitjacker.scrigno.util

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Formatters {

    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun dateTime(epochMillis: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    fun date(epochMillis: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    /** "yesterday, 02:14", "3 days ago"... */
    fun relative(context: Context, epochMillis: Long): String =
        DateUtils.getRelativeDateTimeString(
            context,
            epochMillis,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.WEEK_IN_MILLIS,
            DateUtils.FORMAT_SHOW_TIME,
        ).toString()

    fun yearMonth(epochMillis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    /** "May 2024" / "Maggio 2024". */
    fun monthTitle(month: YearMonth): String {
        val locale = Locale.getDefault()
        return DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** 75_000 -> "1:15". */
    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    fun hour(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)
}
