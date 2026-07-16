package com.qsyncphoto.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "synced_files")
data class SyncedFile(
    @PrimaryKey val filePath: String,
    val md5Hash: String,
    val fileSize: Long,
    val syncedAt: Long = System.currentTimeMillis()
)
