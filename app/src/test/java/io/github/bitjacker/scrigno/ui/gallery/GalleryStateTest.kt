package io.github.bitjacker.scrigno.ui.gallery

import io.github.bitjacker.scrigno.TestData
import io.github.bitjacker.scrigno.data.library.Library
import io.github.bitjacker.scrigno.data.library.LibraryItem
import io.github.bitjacker.scrigno.data.library.Location
import io.github.bitjacker.scrigno.data.media.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class GalleryStateTest {

    // Newest first, as LibraryRepository sorts them.
    private val onlyPhone = LibraryItem(TestData.media(1, TestData.millis(2024, 6, 3)), null)
    private val both = LibraryItem(TestData.media(2, TestData.millis(2024, 6, 1)), TestData.record(20, mediaId = 2))
    private val serverOnly = LibraryItem(null, TestData.record(30, date = TestData.millis(2024, 5, 20)))
    private val older = LibraryItem(TestData.media(4, TestData.millis(2023, 12, 24)), TestData.record(40, mediaId = 4))

    private val library = Library(listOf(onlyPhone, both, serverOnly, older), loaded = true, access = MediaAccess.FULL)

    @Test
    fun locations() {
        assertEquals(Location.DEVICE, onlyPhone.location)
        assertEquals(Location.BOTH, both.location)
        assertEquals(Location.SERVER, serverOnly.location)
        assertEquals("d1", onlyPhone.key)
        assertEquals("r30", serverOnly.key)
        assertEquals(3, library.deviceCount)
        assertEquals(2, library.backedUpCount)
        assertEquals(1, library.serverOnlyCount)
    }

    @Test
    fun monthTitlesAreInsertedBeforeEachMonth() {
        val state = buildGalleryState(library, GalleryFilter.ALL)
        val shape = state.entries.map {
            when (it) {
                is GridEntry.Header -> it.month.toString()
                is GridEntry.Photo -> it.item.key
            }
        }
        assertEquals(listOf("2024-06", "d1", "d2", "2024-05", "r30", "2023-12", "d4"), shape)
        assertEquals(4, state.items.size)
        // Keys are unique: the grid relies on it.
        assertEquals(shape.size, state.entries.map { it.key }.toSet().size)
    }

    @Test
    fun filters() {
        fun keys(filter: GalleryFilter) = buildGalleryState(library, filter).items.map { it.key }
        assertEquals(listOf("d1", "d2", "d4"), keys(GalleryFilter.ON_DEVICE))
        assertEquals(listOf("r30"), keys(GalleryFilter.SERVER_ONLY))
        assertEquals(listOf("d1"), keys(GalleryFilter.NOT_BACKED_UP))
        assertEquals(listOf("d1", "d2", "r30", "d4"), keys(GalleryFilter.ALL))
    }

    @Test
    fun emptyFilterGivesNoEntries() {
        val state = buildGalleryState(Library(listOf(onlyPhone), loaded = true), GalleryFilter.SERVER_ONLY)
        assertTrue(state.entries.isEmpty())
        assertEquals(YearMonth.of(2024, 6), (buildGalleryState(library, GalleryFilter.ALL).entries.first() as GridEntry.Header).month)
    }

    @Test
    fun thumbnails() {
        assertTrue(onlyPhone.thumbnailModel is io.github.bitjacker.scrigno.data.media.MediaThumb)
        assertEquals(null, serverOnly.thumbnailModel)
        val withPreview = LibraryItem(null, TestData.record(31, thumb = "/tmp/31.jpg"))
        assertEquals(java.io.File("/tmp/31.jpg"), withPreview.thumbnailModel)
    }
}
