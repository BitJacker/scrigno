package io.github.bitjacker.scrigno.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class RelativeTimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_790_000_000_000L

    @Test
    fun aBackupThatJustEndedIsNotZeroMinutesAgo() {
        assertEquals("just now", Formatters.relative(context, now - 20_000, now))
        assertFalse(Formatters.relative(context, now - 5 * 60_000, now).contains("just now"))
    }

    @Test
    @Config(qualifiers = "it")
    fun inItalian() {
        assertEquals("adesso", Formatters.relative(context, now - 20_000, now))
    }
}
