package garden.vayne.chorus

import android.os.Bundle
import android.os.Parcel
import android.util.Log

/** Debug measurements contain sizes/timings only, never user text or entity IDs. */
internal fun recordEditorSavedState(bundle: Bundle) {
    val parcel = Parcel.obtain()
    try { parcel.writeBundle(bundle); Log.i("ChorusEditor", "activity_bundle_bytes=${parcel.dataSize()}") }
    finally { parcel.recycle() }
}
internal fun recordEditorWork(kind: String, started: Long, units: Int) {
    Log.i("ChorusEditor", "$kind units=$units main_ms=${(System.nanoTime() - started) / 1_000_000.0}")
}
