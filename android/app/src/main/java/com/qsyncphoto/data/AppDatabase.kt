package com.qsyncphoto.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: SyncedFile)

    @Query("SELECT * FROM synced_files WHERE filePath = :path")
    suspend fun getByPath(path: String): SyncedFile?

    @Query("SELECT * FROM synced_files WHERE md5Hash = :hash")
    suspend fun getByHash(hash: String): SyncedFile?

    @Query("SELECT COUNT(*) FROM synced_files")
    fun getCount(): Flow<Int>

    @Query("DELETE FROM synced_files")
    suspend fun clearAll()
}

@Database(entities = [SyncedFile::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncedFileDao(): SyncedFileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "qsyncphoto_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
