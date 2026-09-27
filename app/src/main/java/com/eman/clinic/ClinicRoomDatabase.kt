package com.eman.clinic

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "meta_key") val key: String,
    @ColumnInfo(name = "meta_value") val value: String
)

@Entity(tableName = "sync_dirty", primaryKeys = ["entity_type", "local_id"])
data class SyncDirtyEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "changed_at", defaultValue = "CURRENT_TIMESTAMP") val changedAt: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int,
    @ColumnInfo(name = "last_error", defaultValue = "''") val lastError: String,
    @ColumnInfo(name = "next_retry_at", defaultValue = "''") val nextRetryAt: String,
    @ColumnInfo(name = "sync_status", defaultValue = "'pending'") val syncStatus: String
)

@Dao
interface SyncStatusDao {
    @Query("SELECT COUNT(*) FROM sync_dirty") fun pendingCount(): Int
    @Query("SELECT COUNT(*) FROM sync_dirty WHERE sync_status='failed'") fun failedCount(): Int
    @Query("SELECT meta_value FROM sync_meta WHERE meta_key=:key LIMIT 1") fun meta(key: String): String?
}

@Database(entities = [SyncMetaEntity::class, SyncDirtyEntity::class], version = 8, exportSchema = false)
abstract class ClinicRoomDatabase : RoomDatabase() {
    abstract fun syncStatusDao(): SyncStatusDao

    companion object {
        @JvmStatic fun open(context: Context, auth: AuthStore): ClinicRoomDatabase {
            val app = context.applicationContext
            val scope = ClinicDatabaseScope.scopeId(auth.clinicId(), auth.userId())
            val name = ClinicDatabaseScope.databaseName(scope)
            val passphrase = DatabaseKeyManager.getOrCreate(app, scope).copyOf()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(app, ClinicRoomDatabase::class.java, name)
                .openHelperFactory(factory)
                .allowMainThreadQueries()
                .build()
        }
    }
}
