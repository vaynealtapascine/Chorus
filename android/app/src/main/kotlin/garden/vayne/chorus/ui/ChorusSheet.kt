package garden.vayne.chorus.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** How far a sheet must be pulled down before letting go closes it. */
private val SheetPull = 140.dp

/** A bottom sheet that takes a deliberate pull to close (owner, 2026-11-02). Material's sheet hides
 * after 56 dp or any small flick, which closed sheets in the middle of an action; here a drag or
 * flick closes it only once it has travelled [SheetPull] (60% of a short sheet), and otherwise it
 * springs back. The scrim, Back and the app's own code still close it at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChorusSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, skipPartiallyExpanded: Boolean = false,
    containerColor: Color = BottomSheetDefaults.ContainerColor, content: @Composable ColumnScope.() -> Unit) {
    val pull = with(LocalDensity.current) { SheetPull.toPx() }
    val guard = remember { SheetPullGuard() }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded) { it != SheetValue.Hidden || guard.allowsHide(pull) }
    guard.state = state
    ModalBottomSheet(onDismissRequest, modifier.onSizeChanged { guard.height = it.height.toFloat() }.pointerInput(guard) {
        // watch (never consume) each touch on the sheet, ahead of its own drag handling
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.pressed()
            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            guard.released()
        }
    }, sheetState = state, containerColor = containerColor, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
private class SheetPullGuard {
    var state: SheetState? = null
    var height = 0f
    private var start = Float.NaN
    private var touching = false
    private var releasedAt = 0L

    private fun offset() = runCatching { state?.requireOffset() }.getOrNull() ?: Float.NaN
    fun pressed() { touching = true; start = offset() }
    fun released() { touching = false; releasedAt = SystemClock.uptimeMillis() }

    /** A hide asked for while (or just after) a finger moved the sheet needs the full pull; one asked
     * for at rest (the scrim, Back, code) is always allowed. */
    fun allowsHide(pull: Float): Boolean {
        val fromTouch = touching || SystemClock.uptimeMillis() - releasedAt < 1500
        val travelled = offset() - start
        if (!fromTouch || travelled.isNaN() || travelled < 1f) return true
        return travelled >= if (height > 0f) minOf(pull, height * .6f) else pull
    }
}
