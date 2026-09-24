package io.github.bitjacker.scrigno.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth
import java.util.Locale

class FormattersTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun durations() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:42", Formatters.duration(42_000))
        assertEquals("1:15", Formatters.duration(75_000))
        assertEquals("1:02:03", Formatters.duration(3_723_000))
    }

    @Test
    fun hours() {
        assertEquals("02:00", Formatters.hour(2))
        assertEquals("23:00", Formatters.hour(23))
    }

    @Test
    fun monthTitlesFollowTheLanguage() {
        Locale.setDefault(Locale.ITALY)
        assertEquals("Maggio 2024", Formatters.monthTitle(YearMonth.of(2024, 5)))
        Locale.setDefault(Locale.US)
        assertEquals("May 2024", Formatters.monthTitle(YearMonth.of(2024, 5)))
    }
}
