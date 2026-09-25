package garden.vayne.chorus.widget

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import garden.vayne.chorus.SearchActivity
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model

/** Four launcher shortcuts follow the first live widget pins; the system account is checked at launch. */
object PinnedShortcuts {
    const val EXTRA_MEMBER_ID = "pinned_member_id"
    const val EXTRA_ACCOUNT_ID = "pinned_account_id"
    private var lastKey: List<String>? = null

    internal fun members(model: Model, pins: List<String>, accountId: String?): List<Member> {
        if (model.isPerson) return emptyList()
        val active = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }.associateBy { it.id }
        return pins.distinct().mapNotNull(active::get).take(4)
    }

    fun refresh(ctx: Context, model: Model) {
        val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val account = garden.vayne.chorus.data.Chorus.get(ctx).device?.accountId
        val members = members(model, WidgetPins.read(ctx, account), account).take(manager.maxShortcutCountPerActivity)
        val key = listOf(account.orEmpty()) + members.flatMap { listOf(it.id, it.shownName, it.color, it.glyph) }
        if (key == lastKey) return
        try {
            val shortcuts = if (account == null) emptyList() else members.map { member ->
                ShortcutInfo.Builder(ctx, "member:$account:${member.id}")
                    .setShortLabel(member.shownName.take(20))
                    .setLongLabel("Switch to ${member.shownName}")
                    .setIcon(Icon.createWithBitmap(Avatars.member(ctx, member.glyph, member.color)))
                    .setIntent(Intent(ctx, SearchActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .putExtra(EXTRA_MEMBER_ID, member.id).putExtra(EXTRA_ACCOUNT_ID, account))
                    .build()
            }
            manager.dynamicShortcuts = shortcuts
            lastKey = key
        } catch (e: Exception) {
            Log.w("ChorusShortcuts", "could not refresh pinned shortcuts", e)
        }
    }
}
