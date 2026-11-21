package dev.opendex

import android.content.Intent
import android.view.Display
import dev.opendex.platform.isMisplacedExternalLaunch

/**
 * Dedicated host activity for external displays with an isolated task affinity so Android and OEM
 * desktop shells (such as Samsung DeX) do not collapse external launches back onto Display 0.
 */
class ExternalDisplayActivity : MainActivity() {
    override fun shouldFinishBeforeContent(launchIntent: Intent?): Boolean {
        val expected = launchIntent?.getIntExtra(EXTRA_TARGET_DISPLAY_ID, Display.INVALID_DISPLAY)
            ?: Display.INVALID_DISPLAY
        val misplaced = isMisplacedExternalLaunch(desktop.currentDisplayId(), expected)
        if (misplaced && expected != Display.INVALID_DISPLAY) {
            model.externalInput.reportExternalLaunchRejected(expected)
        }
        return misplaced
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldFinishBeforeContent(intent)) {
            finishAndRemoveTask()
        }
    }

    companion object {
        const val EXTRA_TARGET_DISPLAY_ID = "dev.opendex.extra.TARGET_DISPLAY_ID"
    }
}
