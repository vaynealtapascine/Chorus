package garden.vayne.chorus.ui

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.RichEditHistory
import garden.vayne.chorus.data.RichInputChange
import garden.vayne.chorus.recordEditorWork

/** One explicit history for keyboard, IME context actions and touch Undo/Redo. Compose's native
 * text-only history is never invoked. The state-based field supplies exact user replacement ranges
 * synchronously (including identical retyping); selection and IME composition remain native.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class RichEditorController(initial: RichEditHistory, private val changed: (RichEditHistory) -> Unit) {
    var history by mutableStateOf(initial)
        private set
    val field = TextFieldState(initial.current.text, TextRange(initial.current.start, initial.current.end))
    fun capture() {
        val next = history.selection(field.selection.start, field.selection.end)
        if (next != history) { history = next; changed(next) }
    }
    fun input(text: String, originalSelection: TextRange, selection: TextRange, changes: List<RichInputChange>) {
        val next = history.input(text, originalSelection.start, originalSelection.end, selection.start, selection.end, changes)
        // Revisions inside a composing word are one undo action. The first composing edit already
        // captured the pre-composition frame; committing it must not expose intermediate IME text.
        history = if (field.composition != null && changes.isNotEmpty()) next.copy(past = history.past) else next
        changed(history)
    }
    private fun apply(next: RichEditHistory) {
        history = next
        field.edit { replace(0, length, next.current.text); selection = TextRange(next.current.start, next.current.end) }
        changed(next)
    }
    fun undo() { capture(); apply(history.undo()) }
    fun redo() { capture(); apply(history.redo()) }
    fun format(edit: MappedFormatting) {
        // A delayed link dialog must not apply a selection captured before further input.
        capture()
        if (field.text.toString() != edit.original.text || field.selection != edit.original.selection || field.composition != null) return
        apply(history.format(edit.value.text, edit.value.selection.start, edit.value.selection.end, edit.sources))
    }
    val value: TextFieldValue get() = TextFieldValue(this.field.text.toString(), this.field.selection, this.field.composition)
}

@Composable
internal fun <T> rememberRichEditor(editor: DurableEditor<T>, initial: RichEditHistory, slot: String,
    change: (T, RichEditHistory) -> T): RichEditorController {
    val controller = remember(editor, slot) { RichEditorController(initial) { history -> editor.value?.let { editor.change(change(it, history)) } } }
    DisposableEffect(editor, controller) {
        editor.captures[controller] = controller::capture
        onDispose { controller.capture(); editor.captures.remove(controller) }
    }
    LaunchedEffect(controller) { snapshotFlow { controller.field.selection }.collect { controller.capture() } }
    return controller
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RichEditorField(controller: RichEditorController, label: String, enabled: Boolean = true,
    minLines: Int = 3, maxLines: Int = Int.MAX_VALUE, formatting: Boolean = false) {
    val field = controller.field
    val interaction = remember { MutableInteractionSource() }
    val colors = fieldColors()
    val main = remember { Handler(Looper.getMainLooper()) }
    val interceptor = remember(controller, enabled) { PlatformTextInputInterceptor { request, next ->
        next.startInputMethod(PlatformTextInputMethodRequest { info ->
            object : InputConnectionWrapper(request.createInputConnection(info), false) {
                override fun performContextMenuAction(id: Int): Boolean {
                    if (id != android.R.id.undo && id != android.R.id.redo) return super.performContextMenuAction(id)
                    if (enabled) {
                        val action = { if (id == android.R.id.undo) controller.undo() else controller.redo() }
                        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
                    }
                    return true
                }
            }
        })
    } }
    val input = remember(controller) { InputTransformation {
        val started = System.nanoTime()
        controller.input(asCharSequence().toString(), originalSelection, selection, List(changes.changeCount) { i ->
            val old = changes.getOriginalRange(i); val new = changes.getRange(i)
            RichInputChange(old.min, old.max, new.min, new.max)
        })
        if (changes.changeCount > 0) recordEditorWork("input", started, length)
    } }
    InterceptPlatformTextInput(interceptor) {
        BasicTextField(field, enabled = enabled, inputTransformation = input, interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), lineLimits = TextFieldLineLimits.MultiLine(minLines, maxLines),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                val undo = (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Z && !event.isShiftPressed
                val redo = (event.isCtrlPressed || event.isMetaPressed) && (event.key == Key.Y || (event.key == Key.Z && event.isShiftPressed))
                if (undo || redo) {
                    if (enabled && event.type == KeyEventType.KeyDown) { if (undo) controller.undo() else controller.redo() }
                    true
                } else false
            }, decorator = TextFieldDecorator { inner ->
                // filled, label inside, as ChorusField (D-124)
                TextFieldDefaults.DecorationBox(value = field.text.toString(), innerTextField = inner, enabled = enabled,
                    singleLine = false, visualTransformation = VisualTransformation.None, interactionSource = interaction,
                    label = { Text(label) }, colors = colors, container = {
                        TextFieldDefaults.Container(enabled, false, interaction, colors = colors,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    })
            })
    }
    Row {
        TextButton(onClick = controller::undo, enabled = enabled && controller.history.past.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
        TextButton(onClick = controller::redo, enabled = enabled && controller.history.future.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Redo") }
    }
    if (formatting) JournalFormatToolbar(controller.value, enabled, onMappedChange = {
        val started = System.nanoTime()
        controller.format(it)
        recordEditorWork("format", started, it.value.text.length)
    }) { }
    // There is no second usable history, including after IME edits. Our preview key handler and
    // InputConnection wrapper consume every exposed platform undo path before this native history.
    SideEffect { field.undoState.clearHistory() }
}
