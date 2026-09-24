package io.github.bitjacker.scrigno

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.bitjacker.scrigno.backup.BackupScheduler
import io.github.bitjacker.scrigno.core.remote.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What a new user does: welcome → permissions → server setup with a real server → main screen. */
@RunWith(AndroidJUnit4::class)
class FirstLaunchUiTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.all)

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun welcomeThenServerSetupThenMainScreen() {
        val server = TestServers.all.firstOrNull { !it.protocol.usesTls && it.protocol != Protocol.SMB }
        assumeTrue("needs a plain test server (WebDAV, FTP or SFTP)", server != null)
        server!!
        runBlocking { resetApp(context) }

        ActivityScenario.launch(MainActivity::class.java).use {
            // Welcome and permissions (already granted by the rule).
            compose.onNodeWithText(context.getString(R.string.onboarding_tagline)).assertIsDisplayed()
            compose.onNodeWithTag("start").performScrollTo().performClick()
            compose.onNodeWithTag("continue").performScrollTo().performClick()

            // Server form.
            compose.onNodeWithTag("protocol_${server.protocol.name}").performScrollTo().performClick()
            compose.onNodeWithTag("host").performScrollTo().performTextInput(server.host)
            compose.onNodeWithTag("port").performScrollTo().performTextReplacement(server.port.toString())
            compose.onNodeWithTag("username").performScrollTo().performTextInput(server.username)
            compose.onNodeWithTag("password").performScrollTo().performTextInput(server.password)
            compose.onNodeWithTag("folder").performScrollTo().performTextInput(server.basePath)

            compose.onNodeWithTag("test").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodesWithTag("test_ok").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("test_failed").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("test_ok").assertIsDisplayed()

            compose.onNodeWithTag("save").performScrollTo().performClick()
            val backupTab = context.getString(R.string.tab_backup)
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodesWithText(backupTab).fetchSemanticsNodes().isNotEmpty()
            }

            // Main screen: the Backup tab offers to back up now.
            compose.onNodeWithText(backupTab).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(context.getString(R.string.action_backup_now)).fetchSemanticsNodes().isNotEmpty()
            }

            val saved = context.container.settings.server.value!!
            assertEquals(server.host, saved.host)
            assertEquals(server.protocol, saved.protocol)
            assertTrue(context.container.settings.settings.value.onboardingDone)

            // The automatic backup is on, and the first one started by itself.
            val work = WorkManager.getInstance(context)
            assertTrue(work.getWorkInfosForUniqueWork(BackupScheduler.NEW_MEDIA_BACKUP).get().isNotEmpty())
            assertTrue(work.getWorkInfosForUniqueWork(BackupScheduler.NEW_MEDIA).get().any { it.state == WorkInfo.State.ENQUEUED })
        }
    }
}
