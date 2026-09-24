package garden.vayne.chorus.ui

import android.content.Context
import android.graphics.Bitmap
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.DeviceRecord

/** Avatars: small images in their own cache (data/Blobs.kt), at most 10 MB and 512 px. */
internal object AvatarBlobs {
    fun load(ctx: Context, hash: String, device: DeviceRecord): Bitmap? =
        Blobs.image(ctx, hash, device, kind = "avatar", maxPx = 512, maxBytes = 10L * 1024 * 1024)
}
