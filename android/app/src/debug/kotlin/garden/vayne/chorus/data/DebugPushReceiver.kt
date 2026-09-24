package garden.vayne.chorus.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Debug builds only: show a push payload as if it had arrived decrypted, to test notifications
 * and the inline Reply without a reachable push server. Only the adb shell can send it (the
 * receiver requires DUMP):
 *
 *   adb shell am broadcast -n garden.vayne.chorus/.data.DebugPushReceiver --es payload '{"t":"message",…}'
 */
class DebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload = intent.getStringExtra("payload") ?: return
        Push.show(context.applicationContext, JSONObject(payload))
    }
}
