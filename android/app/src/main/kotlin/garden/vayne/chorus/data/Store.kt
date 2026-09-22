package garden.vayne.chorus.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * The device's replica on disk: the op table plus a small key/value table (engine meta, HLC,
 * device record). Same write-behind shape as the web's IndexedDB store (CLIENTS.md §1): the core
 * replica is the source of truth in memory, [save] persists what `take_changes` reports.
 */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "chorus.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ops (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    fun get(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(k: String, v: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** All ops as one JSON array (for `CoreReplica.restore`). */
    fun opsJson(): String {
        val sb = StringBuilder("[")
        readableDatabase.rawQuery("SELECT json FROM ops", null).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) sb.append(',')
                sb.append(c.getString(0))
                first = false
            }
        }
        return sb.append(']').toString()
    }

    /** Persist `{"ops": […], "meta"?: …, "hlc_last": "…"}` from `take_changes`, in one transaction. */
    fun save(changesJson: String) {
        val ch = JSONObject(changesJson)
        val ops = ch.optJSONArray("ops")
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (ops != null) {
                for (i in 0 until ops.length()) {
                    val o = ops.getJSONObject(i)
                    db.insertWithOnConflict(
                        "ops", null,
                        ContentValues().apply { put("id", o.getString("id")); put("json", o.toString()) },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            if (ch.has("meta") && !ch.isNull("meta")) put("meta", ch.get("meta").toString())
            put("hlc", ch.optString("hlc_last", ""))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Forget everything on this device (sign out). The server keeps the canonical copy. */
    fun wipe() {
        writableDatabase.execSQL("DELETE FROM ops")
        writableDatabase.execSQL("DELETE FROM kv")
    }
}
