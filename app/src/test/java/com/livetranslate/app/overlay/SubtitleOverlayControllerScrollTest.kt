package com.livetranslate.app.overlay

import com.livetranslate.app.overlay.SubtitleOverlayController.ScrollAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SubtitleOverlayController.scrollAction] — the pure decision
 * behind the auto-scroll policy. The key case is head-truncation of the display
 * buffer: it must keep the view anchored at the bottom, not snap to top.
 */
class SubtitleOverlayControllerScrollTest {

    @Test
    fun newLineScrollsToShowLast() {
        assertEquals(
            ScrollAction.ShowLastLine,
            SubtitleOverlayController.scrollAction(lineCount = 5, previousLineCount = 4, textLength = 200),
        )
    }

    @Test
    fun sameLineCountHoldsSteady() {
        assertEquals(
            ScrollAction.HoldSteady,
            SubtitleOverlayController.scrollAction(lineCount = 4, previousLineCount = 4, textLength = 200),
        )
    }

    @Test
    fun headTruncationKeepsBottomAnchor() {
        // The display buffer dropped ~2 lines from the head but is still long —
        // this is the ~7-minute truncation case. Must NOT snap to top.
        assertEquals(
            ScrollAction.ShowLastLine,
            SubtitleOverlayController.scrollAction(lineCount = 8, previousLineCount = 10, textLength = 600),
        )
    }

    @Test
    fun smallTruncationDropStillAnchorsBottom() {
        assertEquals(
            ScrollAction.ShowLastLine,
            SubtitleOverlayController.scrollAction(lineCount = 3, previousLineCount = 4, textLength = 150),
        )
    }

    @Test
    fun resetToNearEmptySnapsToTop() {
        assertEquals(
            ScrollAction.ResetToTop,
            SubtitleOverlayController.scrollAction(lineCount = 0, previousLineCount = 10, textLength = 0),
        )
    }

    @Test
    fun resetToSingleShortLineSnapsToTop() {
        assertEquals(
            ScrollAction.ResetToTop,
            SubtitleOverlayController.scrollAction(lineCount = 1, previousLineCount = 10, textLength = 10),
        )
    }

    @Test
    fun twoShortLinesAfterManyAreNotTreatedAsReset() {
        // 2 lines, short text: could be a truncation to a short remainder.
        // lineCount > 1 → not a reset, anchor at bottom.
        assertEquals(
            ScrollAction.ShowLastLine,
            SubtitleOverlayController.scrollAction(lineCount = 2, previousLineCount = 10, textLength = 40),
        )
    }
}
