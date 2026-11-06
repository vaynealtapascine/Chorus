package garden.vayne.chorus.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import garden.vayne.chorus.designsystem.ChorusTheme
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.ui.ConfirmDrawer
import garden.vayne.chorus.ui.Drawer
import garden.vayne.chorus.ui.MenuRow
import garden.vayne.chorus.ui.SectionLabel
import androidx.lifecycle.lifecycleScope
import garden.vayne.chorus.R
import garden.vayne.chorus.data.Chorus
import kotlinx.coroutines.launch

/** The launcher asks once for each widget's local scope; existing widgets default to All. */
class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(this, QuickSwitchWidget::class.java)) {
            finish(); return
        }
        lifecycleScope.launch {
            val chorus = Chorus.get(this@WidgetConfigureActivity)
            val model = chorus.awaitModel()
            val accountId = chorus.device?.accountId
            if (accountId == null || model.isPerson) {
                setContent { ChorusTheme { WidgetBlocked { finish() } } }
                return@launch
            }
            val options = listOf(WidgetScope()) +
                model.groups.filter { it.isSubsystem }.map { WidgetScope("subsystem", it.id) } +
                model.groups.filter { it.kind == "group" }.map { WidgetScope("group", it.id) }
            val labels = options.map { scope -> when (scope.kind) {
                "subsystem" -> "Subsystem · ${model.group(scope.id)?.name.orEmpty()}"
                "group" -> "Group · ${model.group(scope.id)?.name.orEmpty()}"
                else -> "All members"
            } }
            fun save(scope: WidgetScope) {
                if (chorus.device?.accountId != accountId) { finish(); return }
                WidgetState(this@WidgetConfigureActivity).setScope(widgetId, accountId, scope)
                manager.updateAppWidget(widgetId, QuickSwitchWidget.views(this@WidgetConfigureActivity, widgetId, model))
                manager.notifyAppWidgetViewDataChanged(widgetId, R.id.w_grid)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
            val current = options.indexOf(WidgetState(this@WidgetConfigureActivity).scope(widgetId, accountId)).coerceAtLeast(0)
            setContent { ChorusTheme { WidgetSetup(labels.toList(), current, { save(options[it]) }) { finish() } } }
        }
    }
}

/** Which members this widget shows: All in Basic, a subsystem or group behind Advanced (a drawer, D-124). */
@Composable
private fun WidgetSetup(labels: List<String>, current: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    var advanced by remember { mutableStateOf(current > 0) }
    Box(Modifier.fillMaxSize().background(LocalChorusPalette.current.bg))
    Drawer(onCancel, "Quick switch widget", "Show all your members, or choose a group for this widget.") {
        MenuRow(labels[0], garden.vayne.chorus.designsystem.R.drawable.ic_members, selected = current == 0) { onPick(0) }
        if (labels.size > 1) {
            if (!advanced) MenuRow("Choose a subsystem or group…", garden.vayne.chorus.designsystem.R.drawable.ic_sliders,
                subtitle = "Advanced") { advanced = true }
            else {
                SectionLabel("Advanced")
                for (i in 1 until labels.size) MenuRow(labels[i], garden.vayne.chorus.designsystem.R.drawable.ic_layers, selected = i == current) { onPick(i) }
            }
        }
    }
}

@Composable
private fun WidgetBlocked(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalChorusPalette.current.bg))
    ConfirmDrawer("Open Chorus first", "Sign in with a system account, then add the widget again.", "OK", onClose, onClose,
        icon = garden.vayne.chorus.designsystem.R.drawable.ic_swap)
}
