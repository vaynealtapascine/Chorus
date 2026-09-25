package garden.vayne.chorus.widget

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
                AlertDialog.Builder(this@WidgetConfigureActivity).setMessage("Open Chorus with a system account first.")
                    .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                return@launch
            }
            val options = listOf(WidgetScope()) +
                model.groups.filter { it.isSubsystem }.map { WidgetScope("subsystem", it.id) } +
                model.groups.filter { it.kind == "group" }.map { WidgetScope("group", it.id) }
            val labels = options.map { scope -> when (scope.kind) {
                "subsystem" -> "Subsystem · ${model.group(scope.id)?.name.orEmpty()}"
                "group" -> "Group · ${model.group(scope.id)?.name.orEmpty()}"
                else -> "All members"
            } }.toTypedArray()
            var selected = options.indexOf(WidgetState(this@WidgetConfigureActivity).scope(widgetId, accountId)).coerceAtLeast(0)
            AlertDialog.Builder(this@WidgetConfigureActivity).setTitle("Widget contents")
                .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setPositiveButton("Save") { _, _ ->
                    if (chorus.device?.accountId != accountId) { finish(); return@setPositiveButton }
                    WidgetState(this@WidgetConfigureActivity).setScope(widgetId, accountId, options[selected])
                    manager.updateAppWidget(widgetId, QuickSwitchWidget.views(this@WidgetConfigureActivity, widgetId, model))
                    manager.notifyAppWidgetViewDataChanged(widgetId, R.id.w_grid)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }.setOnCancelListener { finish() }.show()
        }
    }
}
