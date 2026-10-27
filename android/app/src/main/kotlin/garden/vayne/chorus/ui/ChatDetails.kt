package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendAsSheet(model: Model, channelId: String, members: List<Member>, current: String?,
    onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val presence = ChatPresentation.presence(model, channelId).filter { it.subjectType == "member" }
    val frontId = ChatSpeaker.frontOf(model, channelId)
    val presentIds = if (model.namedFronts == null) presence.filter { it.level == "front" || it.level == "cocon" }.map { it.subjectId }.toSet()
        else model.namedFronts.resolved[frontId]?.authorship.orEmpty().filter { it.subjectType == "member" }.map { it.subjectId }.toSet()
    val frontName = model.namedFronts?.fronts?.get(frontId)?.name ?: "Body"
    val present = presence.filter { it.subjectId in presentIds }.mapNotNull { entry -> members.find { it.id == entry.subjectId } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Send message as…", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            for ((heading, candidates) in listOf("Fronting · $frontName" to present, "Not fronting" to members.filter { it.id !in presentIds })) {
                if (candidates.isNotEmpty()) {
                    Text(heading, color = p.accent, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                    for (member in candidates) {
                        val entry = presence.firstOrNull { it.subjectId == member.id }
                        Row(Modifier.fillMaxWidth().background(if (current == member.id) p.accentSoft else p.surface)
                            .clickable { onChoose(member.id) }.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChatAvatar(member, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(member.shownName, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                val detail = listOfNotNull(ChatPresentation.layer(model, entry), member.pronouns?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (detail.isNotEmpty()) Text(detail, color = p.ink3, fontSize = 13.sp)
                            }
                            if (current == member.id) ChatIcon(R.drawable.ic_check, "Current speaker", p.accent)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = p.line)
            Text("Speaking as someone doesn't change who's fronting.", color = p.ink3, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

