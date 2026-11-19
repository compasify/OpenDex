package dev.opendex

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class WorkspaceUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun drawerFiltersAndCanBeClosed() {
        rule.waitUntil(8_000) { rule.onAllNodesWithContentDescription("Ứng dụng").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Ứng dụng").performClick()
        rule.onNodeWithText("Tìm ứng dụng").performTextInput("___no_result_opendex___")
        rule.onNodeWithText("Không có ứng dụng phù hợp.").assertExists()
        rule.onNodeWithContentDescription("Đóng menu ứng dụng").performClick()
        rule.onNodeWithText("Tìm ứng dụng").assertDoesNotExist()
    }

    @Test fun notesWindowCanMinimizeRestoreAndClose() {
        rule.waitUntil(8_000) { rule.onAllNodesWithContentDescription("Ứng dụng").fetchSemanticsNodes().isNotEmpty() }
        val close = rule.onNodeWithContentDescription("Đóng Ghi chú")
        if (rule.onAllNodesWithContentDescription("Đóng Ghi chú").fetchSemanticsNodes().isNotEmpty()) close.performClick()
        val minimized = rule.onNodeWithContentDescription("Ghi chú (đã thu nhỏ)")
        if (rule.onAllNodesWithContentDescription("Ghi chú (đã thu nhỏ)").fetchSemanticsNodes().isNotEmpty()) {
            minimized.performClick()
            close.performClick()
        }
        rule.onNodeWithContentDescription("Ghi chú").performClick()
        rule.onNodeWithText("Ghi chú nháp").assertExists()
        rule.onNodeWithContentDescription("Thu nhỏ Ghi chú").performClick()
        rule.onNodeWithText("Ghi chú nháp").assertDoesNotExist()
        minimized.performClick()
        rule.onNodeWithText("Ghi chú nháp").assertExists()
        close.performClick()
        rule.onNodeWithText("Ghi chú nháp").assertDoesNotExist()
    }
}
