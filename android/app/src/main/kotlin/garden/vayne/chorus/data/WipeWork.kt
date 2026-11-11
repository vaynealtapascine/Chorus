package garden.vayne.chorus.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Erases what a signed-out phone still holds once its `wipe_at` has passed (D-152), with the app
 * closed too. The deadline is checked again here (and at every app start), never assumed.
 */
class WipeWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val left = Chorus.get(applicationContext).eraseIfDue()
        // the clock moved, or the deadline was later than booked: look again then
        if (left != null && left > 0) schedule(applicationContext, left, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "chorus-wipe"

        fun schedule(ctx: Context, delayMs: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val request = OneTimeWorkRequestBuilder<WipeWork>()
                .setInitialDelay(delayMs.coerceAtLeast(0) + 1_000, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, request)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        }
    }
}
