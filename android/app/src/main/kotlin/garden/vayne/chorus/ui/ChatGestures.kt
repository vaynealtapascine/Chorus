package garden.vayne.chorus.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

/** Swipe to reply: the bubble follows the finger either way with growing resistance (owner, 2026-11-02: both directions). */
internal object SwipeReply {
    /** Shown distance (dp) at which a release replies and the tick fires. */
    const val ThresholdDp = 64f
    /** The bubble never travels further than this, however far the finger does. */
    const val LimitDp = 128f

    /** Rubber band: 1:1 with the finger at first, easing toward [limit]. */
    fun pull(drag: Float, limit: Float): Float = limit * (1f - exp(-drag.coerceAtLeast(0f) / limit))

    /** The finger distance that shows [pulled]; lets a re-grab during the spring-back continue without a jump. */
    fun drag(pulled: Float, limit: Float): Float = -limit * ln(1f - (pulled / limit).coerceIn(0f, .999f))
}

/** Drag state of one message. The shift is a layer offset, so following the finger never recomposes. */
@Stable
internal class ReplySwipe(private val scope: CoroutineScope, private val threshold: Float, private val limit: Float) {
    /** Toward the start is negative, toward the end positive (before the RTL [sign]). */
    private var shift by mutableStateOf(0f)
    private var returning: Job? = null
    /** Signed finger travel, positive toward the start. */
    private var finger = 0f
    var enabled = false
    var motion = true
    var sign = 1f
    var haptic: HapticFeedback? = null
    var onReply: () -> Unit = {}

    /** Pixels the message is moved, as a signed layer translation. */
    val translation: Float get() = shift * sign
    /** Pulled toward the end: the hint waits at the start instead. */
    val towardEnd: Boolean get() = shift > 0f
    /** 0..1 on the way to the threshold. */
    val progress: Float get() = (abs(shift) / threshold).coerceIn(0f, 1f)
    /** Past the threshold: releasing now replies. */
    var armed by mutableStateOf(false); private set
    /** Dragging or springing back; the hint behind the bubble exists only meanwhile. */
    var active by mutableStateOf(false); private set

    fun start() {
        returning?.cancel()
        active = true
        finger = -sign(shift) * SwipeReply.drag(abs(shift), limit)
    }

    fun move(towardStart: Float) {
        finger += towardStart
        val pulled = SwipeReply.pull(abs(finger), limit)
        val crossed = pulled >= threshold
        if (crossed && !armed) haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        armed = crossed
        shift = -sign(finger) * pulled
    }

    fun end(release: Boolean) {
        val reply = release && armed && enabled
        armed = false
        finger = 0f
        if (reply) onReply()
        returning?.cancel()
        returning = scope.launch {
            if (motion) Animatable(shift).animateTo(0f, ChorusMotion.settle()) { shift = value }
            else shift = 0f
            active = false
        }
    }
}

@Composable
internal fun rememberReplySwipe(enabled: Boolean, onReply: () -> Unit): ReplySwipe {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val swipe = remember(density) { ReplySwipe(scope, SwipeReply.ThresholdDp * density, SwipeReply.LimitDp * density) }
    swipe.enabled = enabled
    swipe.motion = rememberChorusMotion()
    swipe.sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    swipe.haptic = LocalHapticFeedback.current
    swipe.onReply = onReply
    return swipe
}

/** Only a horizontal drag is taken; vertical scrolling, taps and long presses stay with their owners. */
internal fun Modifier.swipeToReply(swipe: ReplySwipe): Modifier = if (!swipe.enabled) this else
    pointerInput(swipe) {
        detectHorizontalDragGestures(onDragStart = { swipe.start() }, onDragEnd = { swipe.end(true) },
            onDragCancel = { swipe.end(false) }) { change, amount ->
            change.consume()
            swipe.move(-amount * swipe.sign)
        }
    }

/** The reply icon waiting behind the bubble on the side it leaves: fades and grows with the pull, pops once it is armed. */
@Composable
internal fun BoxScope.ReplySwipeHint(swipe: ReplySwipe) {
    val p = LocalChorusPalette.current
    val pop by animateFloatAsState(if (swipe.armed) 1.2f else 1f, if (swipe.motion) ChorusMotion.pop() else snap(), label = "reply pop")
    Box(Modifier.align(if (swipe.towardEnd) Alignment.CenterStart else Alignment.CenterEnd)
        .padding(horizontal = 8.dp).size(32.dp).graphicsLayer {
        val shown = swipe.progress
        alpha = shown
        scaleX = (.5f + .5f * shown) * pop
        scaleY = scaleX
    }.background(p.surface3, CircleShape), contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_reply, tint = p.accent) }
}

/** A heart that pops over a bubble each time [pops] increases, then fades; nothing at all without motion. */
@Composable
internal fun HeartPop(pops: Int, modifier: Modifier = Modifier) {
    val motion = rememberChorusMotion()
    val scale = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(pops) {
        if (pops == 0 || !motion) return@LaunchedEffect
        fade.snapTo(1f)
        scale.snapTo(.9f)
        launch { scale.animateTo(1f, ChorusMotion.pop()) }
        delay(380)
        fade.animateTo(0f, ChorusMotion.tween(true))
    }
    if (pops > 0 && motion) Text("❤️", fontSize = 44.sp, lineHeight = 52.sp,
        modifier = modifier.graphicsLayer { alpha = fade.value; scaleX = scale.value; scaleY = scale.value })
}
