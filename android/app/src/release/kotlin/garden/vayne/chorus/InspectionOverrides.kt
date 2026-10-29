package garden.vayne.chorus

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds ignore all inspection extras. */
@Composable
internal fun InspectionOverrides(intent: Intent, content: @Composable () -> Unit) = content()

/** Release dialogs use the device configuration and have no inspection state. */
@Composable
internal fun InspectionDialogContent(content: @Composable () -> Unit) = content()
