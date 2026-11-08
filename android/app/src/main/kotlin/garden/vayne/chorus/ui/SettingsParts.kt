package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * A flat settings row (F10.1, F10.2): a 22 dp line icon, a title with an optional line under it, an
 * optional accent [value] or [badge], and a chevron when it opens something. [danger] colours it red
 * (F10.1 "Sync issues").
 */
@Composable
internal fun NavRow(title: String, icon: Int? = null, subtitle: String? = null, value: String? = null, badge: Int = 0,
    danger: Boolean = false, chevron: Boolean = true, enabled: Boolean = true, onClick: (() -> Unit)?) {
    val p = LocalChorusPalette.current
    val ink = when { !enabled -> p.ink3; danger -> p.danger; else -> p.ink }
    Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle != null) 62.dp else 52.dp)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        icon?.let { Glyph(it, if (danger) p.danger else p.ink2, 22.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = if (danger) p.danger.copy(alpha = .8f) else p.ink3, fontSize = 13.sp, lineHeight = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        value?.let { Text(it, color = if (enabled) p.accent else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) }
        if (badge > 0) Box(Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).background(p.accentSoft, CircleShape)
            .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(if (badge > 99) "99+" else badge.toString(), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (chevron && onClick != null) Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** A row that only shows a value (a handle, a server): label at left, value in ink3 at right. */
@Composable
internal fun FormInfoRow(label: String, value: String) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = p.ink3, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
    }
}
