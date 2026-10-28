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
