package garden.vayne.chorus.ui

import garden.vayne.chorus.InspectionDialogContent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.DeviceNavigation

internal fun destinationLabel(name: String) = when(name) {
    "Home" -> "Fronting"; "People" -> "Friends"; "History" -> "Front history"; else -> name
}
private fun destinationIcon(name: String) = when(name) {
    "Home" -> AppIcon.Front; "People" -> AppIcon.Friends; "History" -> AppIcon.History
    "Members" -> AppIcon.Members; "Chat" -> AppIcon.Chat; "Journal" -> AppIcon.Journal; "Notifications" -> AppIcon.Bell; else -> AppIcon.More
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationSettings(selected: List<String>, person: Boolean, onChange: (List<String>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Bottom navigation", style = MaterialTheme.typography.bodyLarge)
            Text((selected + "More").joinToString(" · ", transform = ::destinationLabel),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChorusIcon(AppIcon.Chevron)
    }
    if (open) {
        var draft by rememberSaveable { mutableStateOf(selected) }
        Drawer({ open = false }, "Bottom navigation") {

            run {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Preview", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 12.dp)) {
                        (draft + "More").forEach { name ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ChorusIcon(destinationIcon(name))
                                Text(destinationLabel(name), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    draft.forEachIndexed { index, name ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(destinationLabel(name), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(enabled = index > 0, modifier = Modifier.size(48.dp),
                                onClick = { draft = draft.toMutableList().apply { add(index - 1, removeAt(index)) } }) {
                                ChorusIcon(AppIcon.Chevron, "Move ${destinationLabel(name)} earlier", Modifier.rotate(-90f))
                            }
                            IconButton(enabled = index < draft.lastIndex, modifier = Modifier.size(48.dp),
                                onClick = { draft = draft.toMutableList().apply { add(index + 1, removeAt(index)) } }) {
                                ChorusIcon(AppIcon.Chevron, "Move ${destinationLabel(name)} later", Modifier.rotate(90f))
                            }
                            IconButton(modifier = Modifier.size(48.dp), onClick = { draft = draft - name }) {
                                ChorusIcon(AppIcon.Close, "Remove ${destinationLabel(name)} from navigation")
                            }
                        }
                    }
                    DeviceNavigation.available(person).filter { it !in draft }.forEach { name ->
                        TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = draft.size < 4,
                            onClick = { draft = draft + name }) { ChorusIcon(AppIcon.Plus); Spacer(Modifier.width(8.dp)); Text("Add ${destinationLabel(name)}") }
                    }
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { draft = DeviceNavigation.resolve(null, person) }) { Text("Restore default navigation") }
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { onChange(draft); open = false }) { Text("Apply navigation") }
                }
            }
        }
    }
}
