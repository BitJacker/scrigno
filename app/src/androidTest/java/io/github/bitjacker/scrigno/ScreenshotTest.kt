package io.github.bitjacker.scrigno

import android.app.LocaleManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.bitjacker.scrigno.backup.BackupEngine
import io.github.bitjacker.scrigno.core.remote.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through the main screens with a few demo photos and saves screenshots (in Italian), so
 * that the look of every build can be checked. The files end up in the Gradle "additional test
 * output" folder and are published by the CI.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.all)

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folder = "ScrignoDemo"

    private val outputDir: File? by lazy {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it, "screenshots") }
    }

    @After
    fun tearDown() {
        TestImages.deleteFolder(context, folder)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(700) // let images fade in
        val dir = outputDir ?: return
        dir.mkdirs()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun mainScreens() {
        val server = TestServers.all.firstOrNull { it.protocol == Protocol.WEBDAV }
        assumeTrue("needs the WebDAV test server", server != null && outputDir != null)
        server!!
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("it")
        }
        runBlocking { resetApp(context) }
        TestImages.deleteFolder(context, folder)

        // Welcome screen, as a new user sees it.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Scrigno").fetchSemanticsNodes().isNotEmpty() }
            shot("01-benvenuto")
        }

        // A small gallery: some photos backed up, some only on the server, some only on the phone.
        val colors = listOf(
            Color.rgb(15, 118, 110), Color.rgb(242, 178, 51), Color.rgb(59, 130, 246), Color.rgb(239, 68, 68),
            Color.rgb(16, 185, 129), Color.rgb(139, 92, 246), Color.rgb(236, 72, 153), Color.rgb(245, 158, 11),
            Color.rgb(14, 165, 233), Color.rgb(34, 197, 94), Color.rgb(249, 115, 22), Color.rgb(100, 116, 139),
        )
        val dates = listOf(
            "2026:09:20 18:30:00", "2026:09:18 09:15:00", "2026:09:12 12:00:00", "2026:09:02 20:45:00",
            "2026:08:28 17:10:00", "2026:08:15 11:00:00", "2026:08:14 19:30:00", "2026:08:03 08:05:00",
            "2026:07:22 16:40:00", "2026:07:21 13:20:00", "2026:07:05 10:00:00", "2026:07:01 21:15:00",
        )
        colors.zip(dates).forEachIndexed { index, (color, date) ->
            TestImages.create(context, folder, "IMG_DEMO_%02d.jpg".format(index), date, color)
        }
        val c = context.container
        c.settings.saveServer(server)
        val bucket = c.mediaStore.query().first { it.bucketName == folder }.bucketId
        c.settings.update {
            it.copy(
                onboardingDone = true,
                autoBackup = true,
                keepOnDeviceDays = 30,
                allFolders = false,
                selectedFolders = setOf(bucket),
                deviceFolder = "screenshots-" + System.currentTimeMillis(),
            )
        }
        runBlocking {
            BackupEngine(c).run { false }
            // The oldest photos leave the phone; the two newest are taken "after" the backup.
            val old = c.freeSpace.candidates(0).filter { it.media.bucketName == folder }.sortedBy { it.media.dateTaken }.take(5)
            val verified = c.freeSpace.verify(old) { _, _ -> }
            c.freeSpace.deleteDirectly(verified)
            c.freeSpace.finish(verified)
            TestImages.create(context, folder, "IMG_DEMO_NEW1.jpg", "2026:09:23 19:00:00", Color.rgb(250, 204, 21))
            TestImages.create(context, folder, "IMG_DEMO_NEW2.jpg", "2026:09:22 07:30:00", Color.rgb(168, 85, 247))
            c.library.refresh()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Foto").fetchSemanticsNodes().isNotEmpty() }
            shot("02-galleria")

            compose.onNodeWithText("Backup").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Esegui backup ora").fetchSemanticsNodes().isNotEmpty() }
            shot("03-backup")

            compose.onNodeWithText("Impostazioni").performClick()
            shot("04-impostazioni")

            compose.onNodeWithText("WebDAV (HTTP) · ${server.host}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Il tuo server").fetchSemanticsNodes().isNotEmpty() }
            shot("05-server")
        }

        // The viewer, on a photo that is only on the server.
        val onServer = c.library.library.value.items.first { it.device == null }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Foto").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("gallery_grid").performScrollToKey(onServer.key)
            compose.onNodeWithTag("tile_${onServer.key}").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Solo sul server").fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(2000) // download of the original
            shot("06-foto-dal-server")
        }
    }
}
