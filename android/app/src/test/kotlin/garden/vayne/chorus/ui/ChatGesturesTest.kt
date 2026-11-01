package garden.vayne.chorus.ui

import org.junit.Assert.*
import org.junit.Test

class ChatGesturesTest {
    @Test fun onlyAnArmedReleaseRepliesAndCancellationNeverDoes() {
        var replies = 0
        val swipe = ReplySwipe(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), 64f, 128f)
        swipe.enabled = true; swipe.motion = false; swipe.onReply = { replies++ }
        swipe.start(); swipe.move(200f)
        assertTrue(swipe.armed)
        swipe.end(false)
        assertEquals(0, replies)
        assertEquals(0f, swipe.translation, 0f)
        swipe.start(); swipe.move(200f); swipe.move(-200f); swipe.end(true)
        assertEquals(0, replies)
        swipe.start(); swipe.move(200f); swipe.end(true)
        assertEquals(1, replies)
        assertFalse(swipe.active)
        swipe.start(); swipe.move(200f); swipe.enabled = false; swipe.end(true)
        assertEquals(1, replies)
    }

    @Test fun rtlMirrorsTheVisualOffsetWithoutChangingTheThreshold() {
        val swipe = ReplySwipe(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), 64f, 128f)
        swipe.motion = false; swipe.start(); swipe.move(200f)
        val ltr = swipe.translation
        assertTrue(ltr < 0f)
        swipe.sign = -1f
        assertEquals(-ltr, swipe.translation, 0f)
        assertTrue(swipe.armed)
        swipe.end(false)
    }

    private val limit = SwipeReply.LimitDp

    @Test fun pullFollowsTheFingerAtFirstThenResists() {
        assertEquals(0f, SwipeReply.pull(0f, limit), 0f)
        assertEquals(0f, SwipeReply.pull(-30f, limit), 0f)
        assertEquals(10f, SwipeReply.pull(10f, limit), .5f)
        var previous = 0f
        for (drag in listOf(10f, 40f, 90f, 200f, 1_000f)) {
            val pulled = SwipeReply.pull(drag, limit)
            assertTrue(pulled > previous)
            assertTrue(pulled < drag || drag == 10f)
            assertTrue(pulled < limit)
            previous = pulled
        }
    }

    @Test fun thresholdIsReachableButTakesMoreThanTheShownDistance() {
        val finger = SwipeReply.drag(SwipeReply.ThresholdDp, limit)
        assertTrue(finger > SwipeReply.ThresholdDp)
        assertEquals(SwipeReply.ThresholdDp, SwipeReply.pull(finger, limit), .01f)
    }

    @Test fun dragInvertsPullSoARegrabDoesNotJump() {
        for (drag in listOf(0f, 5f, 64f, 150f)) assertEquals(drag, SwipeReply.drag(SwipeReply.pull(drag, limit), limit), .01f)
        assertTrue(SwipeReply.drag(limit * 2, limit).isFinite())
    }
}
