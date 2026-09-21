package ru.ainur.feldshernotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],qualifiers = "w412dp-h915dp-mdpi")
class ActivityRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualActivityRecreationKeepsEditor() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("newCall").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("newCall").performClick();compose.onNodeWithTag("descriptionField").performTextReplacement("Черновик переживает пересоздание Activity")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("editor").assertExists();compose.onNodeWithTag("descriptionField").assertTextContains("Черновик переживает пересоздание Activity")
        compose.onNodeWithTag("saveCall").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("newCall").fetchSemanticsNodes().isNotEmpty() }
        assertNull(runBlocking { ApplicationProvider.getApplicationContext<NotesApplication>().repository.draft() })
    }
}
