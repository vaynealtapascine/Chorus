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
}

@Database(entities = [StoredOp::class, StoredValue::class], version = 1, exportSchema = true)
abstract class ReplicaDatabase : RoomDatabase() {
    abstract fun replicaDao(): ReplicaDao
}
