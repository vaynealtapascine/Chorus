package garden.vayne.chorus.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import garden.vayne.chorus.MainActivity
import garden.vayne.chorus.R

/**
 * Who is fronting, on the lock screen (D-137, Figma F3.8 "Lock screen glance"): an ongoing,
 * quiet notification a person turns on for this phone. It only reads the local projection, so
 * it is redrawn from the same hook as the front widget, after a local, synced or background-sync
 * change. Off by default; person accounts never offer it.
 */
object LockFront {
    const val CHANNEL = "front_ongoing"
    private const val ID = 0x4C46
    private const val PREFS = "chorus.lock_front"
    private const val KEY = "on"

    /** The quiet line for nobody (the same words as the quick-switch widget). */
    const val NOBODY = "No one fronting"

    /** One layer and the names in it. */
    data class Layer(val name: String, val names: List<String>)

    /** The layers that have someone in them, drawn the way a front card does: nested then listed apart. */
    fun layers(model: Model, accountId: String?): List<Layer> {
        val named = model.namedFronts
        val body = named?.bodyId?.ifEmpty { null } ?: accountId.orEmpty()
        val entries = if (named != null) named.entries(body) else model.current
        val scene = FrontScene.build(model, body, entries, accountId)
        return (scene.nested + scene.separate).map { l ->
            Layer(l.name, l.entries.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name })
        }
    }

    /** "STEERING Foxglove, Amarynthe · CO-CONSCIOUS Nairon · NEARBY Violet", or the quiet line. */
    fun line(layers: List<Layer>): String =
        layers.filter { it.names.isNotEmpty() }.joinToString(" · ") { "${it.name.uppercase()} ${it.names.joinToString(", ")}" }.ifEmpty { NOBODY }

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY, false)

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Turn it on or off for this phone, and show or cancel it at once. */
    fun setOn(ctx: Context, on: Boolean, model: Model) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, on).apply()
        refresh(ctx, model)
    }

    /** Post or redraw the notification, or cancel it when it is off, signed out, or a person account. */
    fun refresh(ctx: Context, model: Model) {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val account = Chorus.get(app).device?.accountId
        if (!isOn(app) || account == null || model === Model.Empty || model.isPerson || !hasPermission(app)) { nm.cancel(ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Front on the lock screen", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val line = line(layers(model, account))
        val home = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        // an Activity intent, so Android asks to unlock before the switcher opens
        val switch = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_SWITCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID, NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_chorus)
            .setContentTitle(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(home)
            .addAction(0, "Switch", switch)
            .build())
    }
}
