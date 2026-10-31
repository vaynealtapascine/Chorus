package garden.vayne.chorus.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google's push for phones without a UnifiedPush app (D-096). Firebase starts itself from the
 * resources the build makes out of the operator's google-services.json; a build without it has no
 * Firebase app, and then there's no token and phones fall back to [NotifyCheck].
 */
object Fcm {
    /** How [Push] marks an FCM registration (in place of a distributor package). */
    const val DISTRIBUTOR = "fcm"
    private const val TAG = "ChorusFcm"

    /** This phone's registration token, or null (no Firebase in this build, no Play services). */
    suspend fun token(ctx: Context): String? {
        if (FirebaseApp.getApps(ctx).isEmpty()) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (!t.isSuccessful) Log.i(TAG, "no FCM token", t.exception)
                cont.resume(if (t.isSuccessful) t.result else null)
            }
        }
    }
}

/** Receives FCM's callbacks (registered in the manifest). */
class FcmService : FirebaseMessagingService() {
    /** A new token (first start, data cleared, rotated): register it again. */
    override fun onNewToken(token: String) = Push.ensure(applicationContext)

    override fun onMessageReceived(message: RemoteMessage) {
        val record = message.data["c"] ?: return
        // this runs on a worker thread and the process may go once it returns: finish here
        runBlocking { Push.onFcm(applicationContext, record) }
    }
}
