package garden.vayne.chorus.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass

/** Debug inspection may override motion without touching device settings. Release never sets it. */
internal val LocalChorusMotionOverride = staticCompositionLocalOf<Boolean?> { null }

/** Whether to animate: false when the system's animator duration scale is off (reduced motion)
 * or a debug inspection override says so. Follows the setting live. */
@Composable
internal fun rememberChorusMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = ValueAnimator.areAnimatorsEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return LocalChorusMotionOverride.current ?: enabled
}

/** Motion tokens: quick, soft and interruptible. Gestures use springs; state changes use tweens. */
internal object ChorusMotion {
    /** Decelerating ease shared with navigation. */
    val Ease = CubicBezierEasing(.16f, 1f, .3f, 1f)
    const val Quick = 140
    const val Standard = 220
    const val Gentle = 320
    /** For things that pop in (reactions, likes): a little overshoot, settles fast. */
    fun <T> pop() = androidx.compose.animation.core.spring<T>(dampingRatio = .55f, stiffness = 600f)
    /** For drags returning home (swipe to reply) and layout settling: no overshoot. */
    fun <T> settle() = androidx.compose.animation.core.spring<T>(dampingRatio = 1f, stiffness = 500f)
    fun <T> tween(motion: Boolean, millis: Int = Standard) =
        androidx.compose.animation.core.tween<T>(if (motion) millis else 0, easing = Ease)
}

/** Shared-axis motion preserves navigation direction. State is owned above this host, so a
 * model refresh or a keyboard edit never starts a transition. Android scales these animations. */
@Composable
internal fun ChorusScene(route: String, depth: Int, modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    val motion = LocalChorusMotionOverride.current ?: enabled
    val sceneStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = ValueAnimator.areAnimatorsEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    AnimatedContent(targetState = route to depth, modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart, label = "Chorus navigation", transitionSpec = {
            val direction = if (targetState.second >= initialState.second) 1 else -1
            val ease = CubicBezierEasing(.16f, 1f, .3f, 1f)
            val enter = fadeIn(tween(if (motion) 180 else 0)) +
                slideInHorizontally(tween(if (motion) 220 else 0, easing = ease)) { if (motion) it / 12 * direction else 0 }
            val exit = fadeOut(tween(if (motion) 100 else 0)) +
                slideOutHorizontally(tween(if (motion) 160 else 0, easing = ease)) { if (motion) -it / 24 * direction else 0 }
            (enter togetherWith exit).using(null)
        }) { (shown, _) ->
        // An outgoing visual snapshot must not expose a second set of actions to touch/TalkBack.
        sceneStates.SaveableStateProvider(shown) {
        Box(Modifier.fillMaxSize().then(if (shown == route) Modifier else Modifier.clearAndSetSemantics { }
            .pointerInput(Unit) { awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            } })) { content(shown) }
        }
    }
}

/**
 * Shows [content] while [value] is non-null, growing in and fading, then shrinking away; while it
 * leaves it keeps showing the last value (owner, 2026-11-07: bars and banners arrive instead of
 * popping in). Reduced motion shows and hides it at once.
 */
@Composable
internal fun <T : Any> Presence(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val motion = rememberChorusMotion()
    // a plain holder, not state: it only needs to outlive the frames of the exit
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    AnimatedVisibility(value != null, modifier,
        enter = if (motion) expandVertically(ChorusMotion.tween(true), Alignment.Top) + fadeIn(ChorusMotion.tween(true)) else EnterTransition.None,
        exit = if (motion) shrinkVertically(ChorusMotion.tween(true, ChorusMotion.Quick), Alignment.Top) + fadeOut(ChorusMotion.tween(true, ChorusMotion.Quick))
            else ExitTransition.None) {
        @Suppress("UNCHECKED_CAST")
        (last[0] as T?)?.let { content(it) }
    }
}
