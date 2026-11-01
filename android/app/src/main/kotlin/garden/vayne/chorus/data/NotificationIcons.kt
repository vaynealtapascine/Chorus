package garden.vayne.chorus.data

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import garden.vayne.chorus.R
import garden.vayne.chorus.designsystem.Tokens
import garden.vayne.chorus.ui.tones
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Resolves and renders chat icons for message notifications, matching `ChannelDisc` in chat lists
 * and badging with the Chorus mark when the platform doesn't add a conversation app badge itself.
 */
internal object NotificationIcons {
    const val BRAND_COLOR = 0xFF247D73.toInt()
    private const val PX = 192
    private val DISCS = arrayOf("#B99CE8", "#F08C7F", "#EEAA62", "#DCA3CC", "#8FB8E8", "#86BFA5")

    data class ChatIconSpec(
        val channelId: String,
        val spaceId: String?,
        val chatTitle: String,
        val senderName: String,
        val glyph: String,
        val color: String?,
        val avatarBlob: String?,
        val direct: Boolean,
        val senderGlyph: String? = null,
        val senderColor: String? = null,
        val senderAvatarBlob: String? = null,
    )

    fun discColor(id: String): String {
        var h = 0
        for (ch in id) h = h * 31 + ch.code
        val idx = ((h.toLong() and 0xFFFFFFFFL) % DISCS.size).toInt()
        return DISCS[idx]
    }

    fun firstGlyph(text: String, generalHash: Boolean): String {
        val trimmed = text.trim()
        if (generalHash && trimmed.equals("general", ignoreCase = true)) return "#"
        if (trimmed.isEmpty()) return "#"
        val cp = trimmed.codePointAt(0)
        return String(Character.toChars(cp)).uppercase()
    }

    private fun JSONObject.str(key: String): String? =
        optString(key).trim().takeIf { has(key) && !isNull(key) && it.isNotEmpty() }

    /** Pure resolution from push payload + optional local replica projection. */
    fun resolveSpec(p: JSONObject, model: Model? = null): ChatIconSpec {
        val channelId = p.optString("channel_id")
        val rawScopeSpace = p.optString("scope").takeIf { it.startsWith("space:") }?.removePrefix("space:")
        val rawChannel = model?.channels?.find { it.id == channelId }
        val parentChannel = if (rawChannel?.kind == "thread") {
            ChatThreads.parentChannel(model, rawChannel)
        } else null
        val effChannel = if (
            rawChannel?.kind == "thread" &&
            rawChannel.avatarBlob == null &&
            rawChannel.color == null &&
            rawChannel.icon.isNullOrBlank()
        ) {
            parentChannel ?: rawChannel
        } else {
            rawChannel
        }
        val spaceId = rawChannel?.spaceId ?: rawScopeSpace
        val localSpace = spaceId?.let { sid -> model?.spaces?.find { it.id == sid } }

        val chatJson = p.optJSONObject("chat")
        val senderJson = p.optJSONObject("sender")
        val rawTitle = p.str("title") ?: "Chorus"
        val splitIdx = rawTitle.indexOf(" \u00b7 ")
        val fallbackSender = if (splitIdx > 0) rawTitle.substring(0, splitIdx) else rawTitle
        val senderName = senderJson?.str("name") ?: fallbackSender

        data class LocalVisual(
            val title: String,
            val glyph: String,
            val color: String?,
            val avatar: String?,
            val direct: Boolean,
        )

        val local: LocalVisual = when {
            localSpace?.kind == "internal" && rawChannel?.isMemberDm == true -> {
                val peer = rawChannel.memberIds
                    .firstOrNull { it != p.optString("reply_as") }
                    ?.let { model?.member(it) }
                    ?: rawChannel.memberIds.firstNotNullOfOrNull { model?.member(it) }
                LocalVisual(
                    title = peer?.shownName ?: senderName,
                    glyph = effChannel?.icon?.takeIf { it.isNotBlank() } ?: peer?.glyph.orEmpty(),
                    color = effChannel?.color ?: peer?.color,
                    avatar = effChannel?.avatarBlob ?: peer?.avatarBlob,
                    direct = true,
                )
            }
            localSpace?.kind == "dm" -> {
                val dmTitle = localSpace.name.takeIf { it.isNotBlank() } ?: chatJson?.str("title") ?: senderName
                LocalVisual(
                    title = dmTitle,
                    glyph = effChannel?.icon?.takeIf { it.isNotBlank() } ?: firstGlyph(dmTitle.removePrefix("@"), false),
                    color = effChannel?.color ?: discColor(localSpace.id),
                    avatar = effChannel?.avatarBlob,
                    direct = true,
                )
            }
            localSpace?.kind == "shared" -> {
                val chanCount = model?.channels?.count { it.spaceId == localSpace.id && it.kind != "thread" } ?: 1
                val cname = effChannel?.name?.trim().orEmpty()
                val sname = localSpace.name.trim()
                val isGeneral = cname.isEmpty() || cname.equals("general", ignoreCase = true)
                val singleGeneral = chanCount <= 1 && isGeneral
                val label = when {
                    singleGeneral && sname.isNotEmpty() -> sname
                    sname.isNotEmpty() && cname.isNotEmpty() -> "$sname \u00b7 #$cname"
                    cname.isNotEmpty() -> "#$cname"
                    sname.isNotEmpty() -> sname
                    else -> "Chorus"
                }
                val glyph = effChannel?.icon?.takeIf { it.isNotBlank() }
                    ?: if (isGeneral && sname.isNotEmpty()) firstGlyph(sname, false)
                    else firstGlyph(cname, true)
                LocalVisual(
                    title = label,
                    glyph = glyph,
                    color = effChannel?.color ?: discColor(localSpace.id),
                    avatar = effChannel?.avatarBlob,
                    direct = false,
                )
            }
            effChannel != null -> LocalVisual(
                title = "#${effChannel.name}",
                glyph = ChannelCompose.glyph(effChannel),
                color = effChannel.color,
                avatar = effChannel.avatarBlob,
                direct = false,
            )
            else -> {
                val kind = p.optString("kind")
                val direct = kind == "dm" || kind == "member_dm"
                val isInternalChannel = kind == "channel"
                val parsedChannel = if (!direct && splitIdx > 0) {
                    rawTitle.substring(splitIdx + 3).trim()
                } else null
                val label = parsedChannel ?: rawTitle
                val glyphSeed = parsedChannel?.removePrefix("#") ?: rawTitle.removePrefix("@")
                LocalVisual(
                    title = label,
                    glyph = firstGlyph(glyphSeed, generalHash = !direct && parsedChannel?.startsWith("#") == true),
                    color = if (isInternalChannel) null else (spaceId ?: channelId.takeIf { it.isNotEmpty() })?.let(::discColor),
                    avatar = null,
                    direct = direct,
                )
            }
        }

        val chatTitle = chatJson?.str("title") ?: local.title
        val direct = if (chatJson != null && chatJson.has("direct")) chatJson.optBoolean("direct") else local.direct
        val avatarBlob = effChannel?.avatarBlob ?: if (chatJson != null) chatJson.str("avatar_blob") else local.avatar
        val color = effChannel?.color ?: if (chatJson != null) chatJson.str("color") else local.color
        val glyph = effChannel?.icon?.takeIf { it.isNotBlank() }
            ?: (if (chatJson != null && chatJson.has("glyph")) chatJson.optString("glyph").trim() else null)
            ?: local.glyph

        return ChatIconSpec(
            channelId = channelId,
            spaceId = spaceId,
            chatTitle = chatTitle,
            senderName = senderName,
            glyph = glyph,
            color = color,
            avatarBlob = avatarBlob,
            direct = direct,
            senderGlyph = senderJson?.str("glyph"),
            senderColor = senderJson?.str("color"),
            senderAvatarBlob = senderJson?.str("avatar_blob"),
        )
    }

    suspend fun renderChatIcon(ctx: Context, device: DeviceRecord?, spec: ChatIconSpec): Bitmap {
        val photo = spec.avatarBlob?.let { hash ->
            withTimeoutOrNull(3_500L) {
                withContext(Dispatchers.IO) {
                    Blobs.image(ctx, hash, device, kind = "avatar", maxPx = 256, maxBytes = 10L * 1024 * 1024)
                }
            }
        }
        return if (photo != null) circleCrop(photo, PX) else renderDisc(ctx, spec.glyph, spec.color, spec.direct, PX)
    }

    private fun circleCrop(src: Bitmap, size: Int): Bitmap {
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val scale = max(size.toFloat() / src.width, size.toFloat() / src.height)
        val dx = (size - src.width * scale) * 0.5f
        val dy = (size - src.height * scale) * 0.5f
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = size * 0.5f
        canvas.drawCircle(r, r, r, paint)
        return out
    }

    private fun renderDisc(ctx: Context, glyph: String, color: String?, direct: Boolean, size: Int): Bitmap {
        val dark = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val palette = if (dark) Tokens.Dark else Tokens.Light
        val (bg, fg) = if (color != null) {
            val t = tones(color, dark)
            t.ring.toArgb() to t.avatarInk.toArgb()
        } else {
            palette.accentSoft.toArgb() to palette.accent.toArgb()
        }
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size * 0.5f
        paint.color = bg
        canvas.drawCircle(r, r, r, paint)
        if (glyph.isEmpty() && direct) {
            ContextCompat.getDrawable(ctx, garden.vayne.chorus.designsystem.R.drawable.ic_members)?.mutate()?.let { d ->
                val iconPx = (size * 0.48f).toInt()
                val left = (size - iconPx) / 2
                d.setBounds(left, left, left + iconPx, left + iconPx)
                d.setTint(fg)
                d.draw(canvas)
                return out
            }
        }
        val text = glyph.ifEmpty { "#" }
        paint.color = fg
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        val count = text.codePointCount(0, text.length)
        paint.textSize = size * (if (count > 1) 0.36f else 0.45f)
        val y = r - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(text, r, y, paint)
        return out
    }

    /** Composites a small circular Chorus icon badge into the bottom-right corner of [base]. */
    fun withChorusBadge(ctx: Context, base: Bitmap): Bitmap {
        val size = base.width
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, null)

        val badgeRadius = size * 0.175f
        val ringGap = size * 0.022f
        val cx = size - badgeRadius - ringGap
        val cy = size - badgeRadius - ringGap

        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawCircle(cx, cy, badgeRadius + ringGap, clearPaint)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3FA597.toInt()
        }
        canvas.drawCircle(cx, cy, badgeRadius, bgPaint)

        ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_foreground)?.mutate()?.let { fg ->
            // ic_launcher_foreground is a 108dp adaptive-icon layer whose "O" mark occupies ~49/108 of the viewport
            val extent = (badgeRadius * 2f * 1.42f).toInt()
            val half = extent / 2
            fg.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx - half).toInt() + extent, (cy - half).toInt() + extent)
            fg.draw(canvas)
        }
        return out
    }

    /**
     * Publishes a long-lived conversation shortcut on Android 11+ so SystemUI places the chat icon
     * at the front of the notification with the Chorus app icon badge in its bottom-right corner.
     */
    fun publishConversationShortcut(
        ctx: Context,
        spec: ChatIconSpec,
        cleanIcon: Bitmap,
        openIntent: Intent,
    ): String? {
        if (Build.VERSION.SDK_INT < 30 || spec.channelId.isEmpty()) return null
        val shortcutId = "chat:${spec.channelId}"
        val label = spec.chatTitle.ifBlank { "Chorus" }
        val shortcut = ShortcutInfoCompat.Builder(ctx, shortcutId)
            .setLongLived(true)
            .setShortLabel(label.take(30))
            .setLongLabel(label.take(64))
            .setIcon(IconCompat.createWithBitmap(cleanIcon))
            .setIntent(openIntent)
            .setPerson(Person.Builder().setName(label).setKey(shortcutId).build())
            .build()
        return runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(ctx, shortcut)
            shortcutId
        }.getOrNull()
    }
}
