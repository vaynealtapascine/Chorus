package garden.vayne.chorus.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.json.JSONObject

/**
 * Room over SQLCipher stores the op replica and engine metadata. Every write from `takeChanges`
 * is one database transaction. The old plaintext prototype is copied and checked before removal.
 */
class Store(private val ctx: Context) {
    private val database: ReplicaDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val file = ctx.getDatabasePath(SECURE_NAME)
        val passphrase = DatabaseKey.passphrase(ctx, file)
        System.loadLibrary("sqlcipher")
        val db = Room.databaseBuilder(ctx, ReplicaDatabase::class.java, SECURE_NAME)
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase, null, true))
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        db.openHelper.writableDatabase // verify the passphrase before reading or migrating anything
        migrateLegacy(db)
        DatabaseKey.markInitialized(ctx)
        db
    }

    private val dao: ReplicaDao get() = database.replicaDao()

    fun get(key: String): String? = dao.get(key)

    fun put(key: String, value: String) {
        dao.put(StoredValue(key, value))
    }

    /** All ops for `CoreReplica.restore`. Stable order makes startup reproducible. */
    fun opsJson(): String = dao.opJson().joinToString(prefix = "[", postfix = "]")

    /** Persist `{"ops": […], "removed"?: […], "meta"?: …, "hlc_last": "…"}` from the Rust replica. */
    fun save(changesJson: String) {
        val changes = JSONObject(changesJson)
        database.runInTransaction {
            changes.optJSONArray("ops")?.let { ops ->
                for (i in 0 until ops.length()) {
                    val op = ops.getJSONObject(i)
                    dao.put(StoredOp(op.getString("id"), op.toString()))
                }
            }
            removedOpIds(changes).takeIf { it.isNotEmpty() }?.let(dao::deleteOps)
            if (changes.has("meta") && !changes.isNull("meta")) {
                dao.put(StoredValue("meta", changes.get("meta").toString()))
            }
            dao.put(StoredValue("hlc", changes.optString("hlc_last", "")))
        }
    }

    /** Forget the local replica only on explicit sign-out. The server's op log remains intact. */
    fun wipe() {
        database.runInTransaction {
            dao.clearOps()
            dao.clearValues()
        }
    }

    private fun migrateLegacy(secure: ReplicaDatabase) {
        val file = ctx.getDatabasePath(LEGACY_NAME)
        if (!file.exists()) return
        val legacy = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            if (secure.replicaDao().get(MIGRATED) != "1") {
                // If an earlier run copied everything but crashed before marking completion,
                // compare it. Never overwrite a secure replica that differs from the old one.
                val hasSecureRows = secure.replicaDao().opCount() > 0 || secure.replicaDao().valueCount() > 0
                if (hasSecureRows) {
                    check(sameRows(legacy, secure)) { "The existing encrypted replica differs from the legacy database." }
                } else {
                    secure.runInTransaction {
                        legacy.rawQuery("SELECT id, json FROM ops", null).use { rows ->
                            while (rows.moveToNext()) secure.replicaDao().put(StoredOp(rows.getString(0), rows.getString(1)))
                        }
                        legacy.rawQuery("SELECT k, v FROM kv", null).use { rows ->
                            while (rows.moveToNext()) secure.replicaDao().put(StoredValue(rows.getString(0), rows.getString(1)))
                        }
                    }
                    check(sameRows(legacy, secure)) { "The encrypted copy did not match the legacy database." }
                }
                secure.replicaDao().put(StoredValue(MIGRATED, "1"))
            }
        } finally {
            legacy.close()
        }
        // The verified encrypted copy is the backup. Context.deleteDatabase removes WAL/SHM too.
        check(ctx.deleteDatabase(LEGACY_NAME) && !file.exists()) {
            "Could not remove the plaintext legacy database after migration."
        }
    }

    private fun sameRows(legacy: SQLiteDatabase, secure: ReplicaDatabase): Boolean {
        val encrypted = secure.openHelper.readableDatabase
        val pairs = listOf(
            "SELECT id, json FROM ops ORDER BY id" to "SELECT id, json FROM ops ORDER BY id",
            "SELECT k, v FROM kv ORDER BY k" to "SELECT k, v FROM kv WHERE k <> '$MIGRATED' ORDER BY k",
        )
        return pairs.all { (oldSql, newSql) ->
            legacy.rawQuery(oldSql, null).use { old ->
                encrypted.query(newSql).use { current -> sameCursor(old, current) }
            }
        }
    }

    private fun sameCursor(a: Cursor, b: Cursor): Boolean {
        while (true) {
            val nextA = a.moveToNext()
            val nextB = b.moveToNext()
            if (nextA != nextB) return false
            if (!nextA) return true
            if (a.getString(0) != b.getString(0) || a.getString(1) != b.getString(1)) return false
        }
    }

    private companion object {
        const val LEGACY_NAME = "chorus.db"
        const val SECURE_NAME = "chorus-secure.db"
        const val MIGRATED = "__chorus_legacy_migrated__"
    }
}

internal fun removedOpIds(changes: JSONObject): List<String> =
    changes.optJSONArray("removed")?.let { ids ->
        (0 until ids.length()).map(ids::getString)
    } ?: emptyList()
