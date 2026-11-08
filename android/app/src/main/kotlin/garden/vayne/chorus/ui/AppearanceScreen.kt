package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.ChorusAccent
import garden.vayne.chorus.designsystem.LocalChorusAppearance
import garden.vayne.chorus.designsystem.LocalChorusDark
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import garden.vayne.chorus.designsystem.Spacing
import garden.vayne.chorus.designsystem.TextSize
import garden.vayne.chorus.designsystem.ThemeMode

/** What Appearance's row in More says: theme · accent · spacing (F10.1 "Dark · teal · comfortable"). */
internal fun appearanceSummary(mode: ThemeMode, accent: ChorusAccent, spacing: Spacing): String =
    listOf(if (mode == ThemeMode.System) "Device" else mode.label, accent.label.lowercase(), spacing.label.lowercase()).joinToString(" · ")

/**
 * F10.2 Appearance, on this device: a live preview, theme, accent, and the reading rows; the rest of
 * the Advanced settings sit behind the last row. Every choice applies at once; the check goes back.
 */
@Composable
internal fun AppearanceScreen(model: Model, onBack: () -> Unit, onAdvanced: () -> Unit) {
    val p = LocalChorusPalette.current
    val a = LocalChorusAppearance.current
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Appearance", "On this device", onBack) { BarIcon(R.drawable.ic_check, "Done", p.accent, onClick = onBack) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            AppearancePreview(model, a.memberColours)
            SectionLabel("Theme", Modifier.padding(horizontal = 12.dp))
            Segmented(ThemeMode.entries.map { it.label }, ThemeMode.entries.indexOf(a.mode), Modifier.padding(horizontal = 16.dp), accent = true) {
                a.change(ThemeMode.entries[it])
            }
            SectionLabel("Accent", Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChorusAccent.entries.forEach { accent -> AccentSwatch(accent, accent == a.accent) { a.update(a.copy(accent = accent)) } }
            }
            SectionLabel("Reading", Modifier.padding(horizontal = 12.dp))
            ToggleRow("Member colours in chat", a.memberColours) { a.update(a.copy(memberColours = it)) }
            NavRow("Text size", value = a.textSize.label, chevron = false) { picking = "text" }
            NavRow("Density", value = a.spacing.label, chevron = false) { picking = "density" }
            NavRow("Advanced", R.drawable.ic_sliders, onClick = onAdvanced)
        }
    }
    ChoiceDrawer(picking == "text", "Text size", TextSize.entries, a.textSize, { it.label },
        { a.update(a.copy(textSize = it)); picking = null }, { picking = null })
    ChoiceDrawer(picking == "density", "Density", Spacing.entries, a.spacing, { it.label },
        { a.update(a.copy(spacing = it)); picking = null }, { picking = null })
}

/** Two bubbles in the chosen look (F10.2): another member's, then ours. */
@Composable
private fun AppearancePreview(model: Model, memberColours: Boolean) {
    val p = LocalChorusPalette.current
    // ours is whoever is speaking now; theirs another member
    val own = model.active.filter { !model.isPerson || it.isSelf }
    val us: Member? = garden.vayne.chorus.data.Reply.speaker(model) ?: own.firstOrNull()
    val them: Member? = own.firstOrNull { it.id != us?.id } ?: us
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (them != null) ChatAvatar(them, 28.dp) else Box(Modifier.size(28.dp).background(p.surface3, CircleShape))
            PreviewBubble(them?.shownName ?: "Amarynthe", them?.color, "Keys are by the door.", false, memberColours)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PreviewBubble(us?.shownName ?: "Foxglove", us?.color, "Got them, thank you!", true, memberColours)
        }
    }
}

@Composable
private fun PreviewBubble(name: String, color: String?, text: String, own: Boolean, memberColours: Boolean) {
    val p = LocalChorusPalette.current
    val tone = color?.let { tonesOf(it) }?.takeIf { memberColours }
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if (own) p.accentSoft else p.surface2).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(name, color = tone?.name ?: p.ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, color = p.ink, fontSize = 16.sp, lineHeight = 22.sp)
            if (own) Glyph(R.drawable.ic_check, p.accent, 14.dp)
        }
    }
}

@Composable
private fun AccentSwatch(accent: ChorusAccent, chosen: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val tone = if (LocalChorusDark.current) accent.dark.accent else accent.light.accent
    Column(Modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { selected = chosen }.padding(vertical = 4.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(40.dp).then(if (chosen) Modifier.border(2.dp, p.ink, CircleShape) else Modifier).padding(3.dp)
            .background(tone, CircleShape))
        Text(accent.label, color = if (chosen) p.ink else p.ink3, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}
