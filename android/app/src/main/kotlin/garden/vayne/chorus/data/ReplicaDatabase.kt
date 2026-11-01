package garden.vayne.chorus.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** Durable input to the pure Rust replica. The projected UI model is rebuilt from these ops. */
@Entity(tableName = "ops")
data class StoredOp(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "kv")
data class StoredValue(@PrimaryKey val k: String, val v: String)

@Dao
interface ReplicaDao {
    @Query("SELECT v FROM kv WHERE k = :key")
    fun get(key: String): String?

    @Query("SELECT json FROM ops ORDER BY id")
    fun opJson(): List<String>

    @Query("SELECT COUNT(*) FROM ops")
    fun opCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM ops WHERE json_extract(json, '$.kind') = 'post.create' AND json_extract(json, '$.scope') = :scope AND json_extract(json, '$.entity_id') = :postId)")
    fun hasCreatedPost(scope: String, postId: String): Boolean

    @Query("SELECT COUNT(*) FROM kv")
    fun valueCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(op: StoredOp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(value: StoredValue)

    @Query("DELETE FROM ops")
    fun clearOps()

    @Query("DELETE FROM ops WHERE id IN (:ids)")
    fun deleteOps(ids: List<String>)

    @Query("DELETE FROM kv")
    fun clearValues()

    @Query("DELETE FROM kv WHERE k = :key")
    fun deleteValue(key: String)

    @Query("SELECT k FROM kv WHERE k LIKE 'editor-draft-v1:%'")
    fun editorDraftKeys(): List<String>

    @Query("SELECT substr(CAST(v AS BLOB), :start, :length) FROM kv WHERE k = :key")
    fun valueChunk(key: String, start: Int, length: Int): ByteArray?
}

@Database(entities = [StoredOp::class, StoredValue::class], version = 1, exportSchema = true)
abstract class ReplicaDatabase : RoomDatabase() {
    abstract fun replicaDao(): ReplicaDao
}
