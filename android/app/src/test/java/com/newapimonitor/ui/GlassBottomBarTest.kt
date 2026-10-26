package com.newapimonitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newapimonitor.ui.theme.NewApiMonitorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Input/semantics tests exercise the clear-glass drawing path on the JVM.
 * Behaviour ported from flutter-liquid-glass skill @ d42aa49: tap fires
 * onChanged on every resolve, drag previews 1:1 and snaps nearest with a
 * 0.15s fling projection, drag alignment clamped to the tab zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class GlassBottomBarTest {
    @get:Rule val rule = createComposeRule()
    private val current = mutableStateOf(TAB_DASHBOARD)
    private val commits = mutableListOf<String>()
    private val tabs = listOf(TAB_DASHBOARD, TAB_LOGS, TAB_TOKENS, TAB_SETTINGS)

    private fun bar(initial: String = TAB_DASHBOARD) {
        current.value = initial
        rule.setContent {
            NewApiMonitorTheme {
                Box(Modifier.width(400.dp)) {
                    GlassBottomBar(current.value, { commits += it; current.value = it }, rememberAppBackdrop())
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bubbleBounds(): Rect =
        rule.onAllNodesWithTag("glass-bubble")[0].fetchSemanticsNode().boundsInRoot

    private fun barBounds(): Rect =
        rule.onNodeWithTag("glass-bar").fetchSemanticsNode().boundsInRoot

    private fun assertBubbleInsideCapsule() {
        val b = bubbleBounds()
        val bar = barBounds()
        assertTrue("bubble left ${b.left} must not escape capsule ${bar.left}", b.left >= bar.left - 2f)
        assertTrue("bubble right ${b.right} must not escape capsule ${bar.right}", b.right <= bar.right + 2f)
    }

    @Test fun tapCommitsSelectionOnEveryResolve() {
        bar()
        rule.onNodeWithTag("tab-$TAB_TOKENS").performTouchInput { click() }
        rule.onNodeWithTag("tab-$TAB_TOKENS").assertIsSelected()
        // The skill reference fires onChanged on every resolved tap, even re-taps.
        rule.onNodeWithTag("tab-$TAB_TOKENS").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(TAB_TOKENS, TAB_TOKENS), commits) }
    }

    @Test fun accessibleSelectionHasTabRoleAndFullHeightTargets() {
        bar()
        tabs.forEach { id ->
            rule.onNodeWithTag("tab-$id")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag("tab-$TAB_SETTINGS").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(listOf(TAB_SETTINGS), commits) }
    }

    @Test fun slowDragPreviewsWithoutCommitThenSnapsNearestOnRelease() {
        bar()
        rule.onNodeWithTag("glass-bar").performTouchInput {
            down(Offset(width * .125f, centerY))
            moveTo(Offset(width * .30f, centerY), delayMillis = 200)
            moveTo(Offset(width * .48f, centerY), delayMillis = 200)
        }
        rule.onNodeWithTag("tab-$TAB_DASHBOARD").assertIsSelected()
        rule.runOnIdle { assertTrue(commits.isEmpty()) }
        rule.onNodeWithTag("glass-bar").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf(TAB_LOGS), commits) }
        rule.onNodeWithTag("tab-$TAB_LOGS").assertIsSelected()
    }

    @Test fun snapIndexProjectsWithFlingVelocityAndClamps() {
        val tw = 233.5f // one tab cell in px (matches the 400dp test layout)
        // Pure displacement: 0.3 cell stays, 0.6 cell advances, -0.6 retreats.
        assertEquals(0, snapIndex(0, dragPx = 0.3f * tw, velocityX = 0f, tabWidth = tw, count = 4))
        assertEquals(1, snapIndex(0, dragPx = 0.6f * tw, velocityX = 0f, tabWidth = tw, count = 4))
        // Fling projection: small drag + fast flick (0.15s of velocity) crosses a cell.
        assertEquals(1, snapIndex(0, dragPx = 0.3f * tw, velocityX = 1f * tw / 0.15f, tabWidth = tw, count = 4))
        assertEquals(0, snapIndex(1, dragPx = -0.3f * tw, velocityX = -1f * tw / 0.15f, tabWidth = tw, count = 4))
        // Both ends clamp.
        assertEquals(3, snapIndex(3, dragPx = 2f * tw, velocityX = 5f * tw / 0.15f, tabWidth = tw, count = 4))
        assertEquals(0, snapIndex(0, dragPx = -5f * tw, velocityX = 0f, tabWidth = tw, count = 4))
        // Degenerate geometry keeps the current index.
        assertEquals(2, snapIndex(2, dragPx = 10f, velocityX = 100f, tabWidth = 0f, count = 4))
    }

    @Test fun slowShortDragWithoutVelocityStaysOnCurrentTab() {
        bar()
        // Same displacement as the fling test but slow: no velocity, no commit.
        rule.onNodeWithTag("glass-bar").performTouchInput {
            down(Offset(width * .125f, centerY))
            moveTo(Offset(width * .165f, centerY), delayMillis = 300)
            moveTo(Offset(width * .20f, centerY), delayMillis = 300)
            up()
        }
        rule.runOnIdle { assertEquals(listOf(TAB_DASHBOARD), commits) }
        rule.onNodeWithTag("tab-$TAB_DASHBOARD").assertIsSelected()
    }

    @Test fun pointerCancellationDoesNotCommit() {
        bar()
        rule.onNodeWithTag("glass-bar").performTouchInput {
            down(Offset(width * .125f, centerY))
            moveTo(Offset(width * .8f, centerY))
            cancel()
        }
        rule.onNodeWithTag("tab-$TAB_DASHBOARD").assertIsSelected()
        rule.runOnIdle { assertTrue(commits.isEmpty()) }
    }

    @Test fun dragStartSnapsBubbleToCommittedCellNotFinger() {
        bar()
        rule.onNodeWithTag("glass-bar").performTouchInput {
            down(Offset(width * .8f, centerY))
        }
        rule.waitForIdle()
        // Press swell (1.03x bar, 1.08x bubble) shifts boundsInRoot, so assert
        // the zone: the bubble must still sit on the committed first cell —
        // nowhere near the finger at 80% of the bar.
        val b = bubbleBounds()
        val bar = barBounds()
        assertTrue("bubble center ${b.center.x} must stay in the first cell zone",
            b.center.x < bar.left + (bar.width / nCells()))
        rule.onNodeWithTag("glass-bar").performTouchInput { cancel() }
    }

    private fun nCells(): Int = 4

    @Test fun extremeDragsClampBubbleInsideCapsule() {
        bar()
        rule.onNodeWithTag("glass-bar").performTouchInput {
            down(Offset(width * .125f, centerY))
            moveTo(Offset(-200f, centerY), delayMillis = 100)
        }
        rule.waitForIdle()
        assertBubbleInsideCapsule()
        rule.onNodeWithTag("glass-bar").performTouchInput {
            moveTo(Offset(width + 200f, centerY), delayMillis = 100)
        }
        rule.waitForIdle()
        assertBubbleInsideCapsule()
        rule.onNodeWithTag("glass-bar").performTouchInput { cancel() }
    }

    @Test fun holdingDownDoesNotChangeSelection() {
        bar()
        rule.onNodeWithTag("tab-$TAB_SETTINGS").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("tab-$TAB_DASHBOARD").assertIsSelected()
        rule.runOnIdle { assertTrue(commits.isEmpty()) }
        rule.onNodeWithTag("tab-$TAB_SETTINGS").performTouchInput { cancel() }
    }

    @Test fun initialAndExternalIndicesSynchronizeWithoutPreviewLoss() {
        bar(TAB_SETTINGS)
        rule.onNodeWithTag("tab-$TAB_SETTINGS").assertIsSelected()
        rule.runOnIdle { current.value = TAB_LOGS }
        rule.onNodeWithTag("tab-$TAB_LOGS").assertIsSelected()
        rule.runOnIdle { assertTrue(commits.isEmpty()) }
    }

    @Test fun externalChangeMidDragCancelsPreviewAndReleaseResolvesNewIndex() {
        bar()
        rule.onNodeWithTag("glass-bar").performTouchInput {
            down(Offset(width * .125f, centerY))
            moveTo(Offset(width * .6f, centerY), delayMillis = 200)
        }
        rule.runOnIdle { current.value = TAB_TOKENS }
        rule.onNodeWithTag("tab-$TAB_TOKENS").assertIsSelected()
        // Release with no further movement/velocity: resolves to the externally set index.
        rule.onNodeWithTag("glass-bar").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf(TAB_TOKENS), commits) }
        rule.onNodeWithTag("tab-$TAB_TOKENS").assertIsSelected()
    }
}
