package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date

/** The switch log, newest first (web History, compact). */
@Composable
fun History(model: Model) {
    val p = LocalChorusPalette.current
    val rows = model.switches.asReversed()
    val day = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (rows.isEmpty()) item { Text("No switches yet.", color = p.ink3, modifier = Modifier.padding(vertical = 24.dp)) }
        var lastDay = ""
        rows.forEach { s ->
            val d = day.format(Date(s.occurredAt))
            if (d != lastDay) {
                lastDay = d
                item(key = "d:$d:${s.id}") { Text(d, color = p.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
            }
            item(key = s.id) {
                val lead = s.resultingFront.firstOrNull { it.level == "front" }?.let { model.subject(it.subjectType, it.subjectId) }
                Row(
                    Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(lead?.glyph ?: "·", lead?.color ?: "#A09184", 32.dp, avatarBlob = lead?.avatarBlob)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val what = when (s.kind) {
                            "add" -> "+ " + model.frontLabel(s.entries)
                            "remove" -> "− " + s.entries.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name }.joinToString(", ").ifEmpty { "someone" }
                            else -> model.frontLabel(s.resultingFront)
                        }
                        Text(
                            what, color = if (s.retracted) p.ink3 else p.ink, fontWeight = FontWeight.Medium,
                            textDecoration = if (s.retracted) TextDecoration.LineThrough else null,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        s.note?.let { Text(it, color = p.ink2, fontSize = 12.sp) }
                    }
                    Text(time.format(Date(s.occurredAt)) + if (s.retracted) " · undone" else "", color = p.ink3, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
