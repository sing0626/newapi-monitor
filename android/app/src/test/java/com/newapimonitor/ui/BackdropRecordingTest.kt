package com.newapimonitor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recording contract only. Robolectric cannot establish real RuntimeShader lens quality. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackdropRecordingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sourceRecordsChildContentAgainAfterContentInvalidation() {
        var childDraws = 0
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            val backdrop = rememberAppBackdrop()
            Box(Modifier.size(100.dp).layerBackdrop(backdrop)) {
                Box(Modifier.size(100.dp).drawBehind {
                    childDraws++
                    drawRect(color.value)
                })
            }
        }
        fun drawAndCount(): Int {
            var count = 0
            rule.runOnIdle {
                val view = (rule as AndroidComposeTestRule<*, *>).activity.window.decorView
                childDraws = 0
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                count = childDraws
                bitmap.recycle()
            }
            return count
        }
        // layerBackdrop draws once on screen and calls our onDraw again to record the source.
        // Omitting drawContent() in rememberAppBackdrop reduces this to just one child draw.
        assertTrue("child must appear in both display and recorded source", drawAndCount() >= 2)
        rule.runOnIdle { color.value = Color.Blue }
        assertTrue("updated child must be recorded, not only the background tint", drawAndCount() >= 2)
    }
}
