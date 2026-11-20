package dev.opendex.platform

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeformTasksTest {
    @Test fun selectsOnlyVisibleMatchingTaskInFreeformOnSameDisplayAndUser() {
        val stacks = """
            RootTask id=1 bounds=[0,0][2560,1600] displayId=0 userId=0
             configuration={winConfig={ mWindowingMode=fullscreen }}
              taskId=1: example.app/.Main bounds=[0,0][2560,1600] userId=0 visible=true
            RootTask id=2 bounds=[0,24][2560,1400] displayId=5 userId=0
             configuration={winConfig={ mWindowingMode=freeform }}
              taskId=2: example.app/.Main bounds=[0,24][2560,1400] userId=0 visible=true
            RootTask id=3 bounds=[0,24][2560,1400] displayId=0 userId=0
             configuration={winConfig={ mWindowingMode=freeform }}
              taskId=3: example.app/.Main bounds=[0,24][2560,1400] userId=10 visible=true
              taskId=4: example.app/.Main bounds=[0,24][2560,1400] userId=0 visible=false
              taskId=5: example.app/.Other bounds=[0,24][2560,1400] userId=0 visible=true
              taskId=6: example.app/example.app.Main bounds=[-5,30][2560,1400] userId=0 visible=true
        """.trimIndent()
        assertEquals(FreeformTask(6, IntRect(-5, 30, 2560, 1400)),
            findVisibleFreeformTask(stacks, "example.app/example.app.Main", "example.app/.Main", 0, 0))
        assertNull(findVisibleFreeformTask(stacks, "unrelated/.Main", "unrelated/.Main", 0, 0))
    }

    @Test fun findsRootTasksOnVirtualDisplayAndMatchesComponentAcrossDisplays() {
        val stacks = """
            RootTask id=41 bounds=[0,0][1200,900] displayId=7 userId=0
             configuration={winConfig={ mWindowingMode=fullscreen }}
              taskId=101: com.miui.calculator/.cal.CalculatorActivity bounds=[0,0][1200,900] userId=0 visible=true
            RootTask id=42 bounds=[0,0][2560,1600] displayId=0 userId=0
             configuration={winConfig={ mWindowingMode=fullscreen }}
              taskId=102: com.android.chrome/com.google.android.apps.chrome.Main bounds=[0,0][2560,1600] userId=0 visible=false
            RootTask id=43 bounds=[0,0][1200,900] displayId=7 userId=0
             configuration={winConfig={ mWindowingMode=fullscreen }}
              taskId=103: com.example/.Other bounds=[0,0][1200,900] userId=0 visible=true
        """.trimIndent()
        assertEquals(listOf(41, 43), findRootTaskIdsOnDisplay(stacks, 7))
        assertEquals(
            RootTaskMatch(41, 7),
            findComponentRootTask(stacks, "com.miui.calculator/com.miui.calculator.cal.CalculatorActivity", "com.miui.calculator/.cal.CalculatorActivity", 0),
        )
        assertEquals(
            RootTaskMatch(42, 0),
            findComponentRootTask(stacks, "com.android.chrome/com.google.android.apps.chrome.Main", "com.android.chrome/.Main", 0),
        )
        assertNull(findComponentRootTask(stacks, "com.miui.calculator/com.miui.calculator.cal.CalculatorActivity", "com.miui.calculator/.cal.CalculatorActivity", 10))
    }
}
