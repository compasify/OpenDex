package dev.opendex

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.opendex.desktop.ConnectedDisplays
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.platform.ScreenInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectedDisplayUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun launchControlTracksDisplayPermissionAndDisconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = ScreenInfo(7, "Test display", 1920, 1080, canOpenWorkspace = true)
        val caps = mutableStateOf(DesktopCapabilities(screens = listOf(external)))
        var requestedDisplay: Int? = null
        rule.setContent {
            MaterialTheme {
                Column { ConnectedDisplays(caps.value) { requestedDisplay = it } }
            }
        }
        val launch = rule.onNodeWithText(context.getString(R.string.open_workspace_on_display, external.id))
        launch.performClick()
        rule.runOnIdle {
            assertEquals(external.id, requestedDisplay)
            caps.value = caps.value.copy(screens = listOf(external.copy(canOpenWorkspace = false)))
        }
        launch.assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.display_direct_launch_unavailable)).assertExists()

        rule.runOnIdle { caps.value = caps.value.copy(currentDisplay = external.id) }
        rule.onNodeWithText(context.getString(R.string.display_current)).assertExists()

        rule.runOnIdle { caps.value = DesktopCapabilities() }
        rule.onNodeWithText(context.getString(R.string.no_external_display)).assertExists()
        launch.assertDoesNotExist()
    }
}
