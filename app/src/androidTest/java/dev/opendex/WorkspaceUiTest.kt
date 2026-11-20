package dev.opendex

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class WorkspaceUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int, vararg values: Any) = rule.activity.getString(id, *values)
    private fun waitForDock() {
        val apps = text(R.string.apps)
        rule.waitUntil(8_000) { rule.onAllNodesWithContentDescription(apps).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun drawerFiltersAndCanBeClosed() {
        waitForDock()
        rule.onNodeWithContentDescription(text(R.string.apps)).performTouchInput { click() }
        val search = rule.onNodeWithText(text(R.string.search_apps))
        search.assertIsNotFocused()
        search.performTouchInput { click() }.assertIsFocused()
        search.performTextInput("___no_result_opendex___")
        rule.onNodeWithText(text(R.string.no_results)).assertExists()
        rule.onNodeWithContentDescription(text(R.string.close_app_drawer)).performClick()
        rule.onNodeWithText(text(R.string.search_apps)).assertDoesNotExist()
    }

    @Test fun notesWindowCanMinimizeRestoreAndClose() {
        waitForDock()
        val notes = text(R.string.notes)
        val closeLabel = text(R.string.close_tool, notes)
        val minimizedLabel = text(R.string.minimized_tool, notes)
        val close = rule.onNodeWithContentDescription(closeLabel)
        val minimized = rule.onNodeWithContentDescription(minimizedLabel)
        if (rule.onAllNodesWithContentDescription(minimizedLabel).fetchSemanticsNodes().isNotEmpty()) {
            minimized.performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(closeLabel).fetchSemanticsNodes().isNotEmpty() }
        }
        if (rule.onAllNodesWithContentDescription(closeLabel).fetchSemanticsNodes().isNotEmpty()) {
            close.performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(closeLabel).fetchSemanticsNodes().isEmpty() }
        }
        val noteField = text(R.string.note_draft)
        rule.onNodeWithContentDescription(notes).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(noteField).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(text(R.string.minimize_tool, notes)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(noteField).fetchSemanticsNodes().isEmpty() }
        minimized.performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(noteField).fetchSemanticsNodes().isNotEmpty() }
        close.performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(noteField).fetchSemanticsNodes().isEmpty() }
    }
}
