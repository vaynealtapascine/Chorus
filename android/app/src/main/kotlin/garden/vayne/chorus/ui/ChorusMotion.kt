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

/** Shared-axis motion preserves navigation direction. State is owned above this host, so a
 * model refresh or a keyboard edit never starts a transition. Android scales these animations. */
@Composable
internal fun ChorusScene(route: String, depth: Int, modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
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
            val enter = fadeIn(tween(if (enabled) 180 else 0)) +
                slideInHorizontally(tween(if (enabled) 220 else 0, easing = ease)) { if (enabled) it / 12 * direction else 0 }
            val exit = fadeOut(tween(if (enabled) 100 else 0)) +
                slideOutHorizontally(tween(if (enabled) 160 else 0, easing = ease)) { if (enabled) -it / 24 * direction else 0 }
            (enter togetherWith exit).using(null)
        }) { (shown, _) -> content(shown) }
}
