package app.stratos.host.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rootPath: String,      // absolute path of the SITE ROOT (after detection)
    val extractDir: String,    // absolute path of the extraction dir
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val spa: Boolean = false,
    val filesCount: Int = 0,
    val sizeBytes: Long = 0,
    val hasIndex: Boolean = false,
    val language: String = "en",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface SiteDao {

    @Query("SELECT * FROM sites ORDER BY isDefault DESC, createdAt DESC")
    fun observeAll(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites ORDER BY isDefault DESC, createdAt DESC")
    suspend fun all(): List<SiteEntity>

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun byId(id: String): SiteEntity?

    @Query("SELECT * FROM sites WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultSite(): SiteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: SiteEntity)

    @Update
    suspend fun update(site: SiteEntity)

    @Query("DELETE FROM sites WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sites SET isDefault = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("UPDATE sites SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Database(entities = [SiteEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun siteDao(): SiteDao
}
