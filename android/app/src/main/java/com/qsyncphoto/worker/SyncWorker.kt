package com.qsyncphoto.worker

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qsyncphoto.data.AppDatabase
import com.qsyncphoto.data.SyncedFile
import com.qsyncphoto.network.ApiService
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val apiService = ApiService()
    private val db = AppDatabase.getDatabase(appContext)
    private val dao = db.syncedFileDao()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("qsyncphoto_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)
        val token = prefs.getString("device_token", null)

        if (serverUrl.isNullOrEmpty() || token.isNullOrEmpty()) {
            return Result.failure(workDataOf("error" to "Brak konfiguracji połączenia z serwerem."))
        }

        try {
            // 1. Scan MediaStore for Camera Images (DCIM)
            val mediaFiles = scanDCIM()
            if (mediaFiles.isEmpty()) {
                return Result.success(workDataOf("uploaded" to 0, "message" to "Brak nowych zdjęć do synchronizacji."))
            }

            var uploadedCount = 0
            var processedCount = 0
            val totalToProcess = mediaFiles.size

            // 2. Process in batches to verify MD5s with server
            val batchSize = 30
            val chunks = mediaFiles.chunked(batchSize)

            for ((chunkIndex, chunk) in chunks.withIndex()) {
                val toCheckOnServer = mutableListOf<MediaFileInfo>()
                
                for (media in chunk) {
                    // Check local DB first
                    val localRecord = dao.getByPath(media.filePath)
                    if (localRecord != null) {
                        continue
                    }

                    // Compute MD5
                    val md5 = computeMD5ForUri(media.uri)
                    if (md5 != null) {
                        // Check if another path has the same MD5 in local DB
                        val md5Record = dao.getByHash(md5)
                        if (md5Record != null) {
                            // File exists locally under a different path, mark as synced
                            dao.insert(SyncedFile(media.filePath, md5, media.size))
                            continue
                        }
                        toCheckOnServer.add(media.copy(md5 = md5))
                    }
                }

                if (toCheckOnServer.isEmpty()) continue

                // Check with server which MD5s are already present
                val md5sToCheck = toCheckOnServer.map { it.md5!! }
                val serverSyncedMd5s = try {
                    apiService.checkAlreadySynced(serverUrl, token, md5sToCheck)
                } catch (e: Exception) {
                    // If network fails, skip this batch or retry
                    return Result.retry()
                }

                // If already synced on server, mark in local DB and separate from upload list
                val serverSyncedSet = serverSyncedMd5s.toHashSet()
                val alreadySyncedOnServer = toCheckOnServer.filter { it.md5 in serverSyncedSet }
                alreadySyncedOnServer.forEach { matchingMedia ->
                    dao.insert(SyncedFile(matchingMedia.filePath, matchingMedia.md5!!, matchingMedia.size))
                    processedCount++
                }
                val toUpload = toCheckOnServer.filter { it.md5 !in serverSyncedSet }

                // Upload remaining files
                for (media in toUpload) {
                    val tempFile = createTempFileFromUri(media.uri, media.displayName) ?: continue
                    
                    try {
                        val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(media.dateAdded * 1000))
                        
                        val success = apiService.uploadFile(
                            serverUrl = serverUrl,
                            token = token,
                            file = tempFile,
                            md5 = media.md5!!,
                            creationDateIso = isoDate
                        )

                        if (success) {
                            dao.insert(SyncedFile(media.filePath, media.md5, media.size))
                            uploadedCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        processedCount++
                        tempFile.delete() // clean up cache
                        // Report progress to UI (based on total processed, capped at 100)
                        val progress = minOf((processedCount * 100) / totalToProcess, 100)
                        setProgress(workDataOf(
                            "progress" to progress,
                            "currentFile" to media.displayName,
                            "uploaded" to uploadedCount
                        ))
                    }
                }
            }

            return Result.success(workDataOf(
                "uploaded" to uploadedCount,
                "message" to "Synchronizacja zakończona. Przesłano plików: $uploadedCount"
            ))

        } catch (e: Exception) {
            return Result.failure(workDataOf("error" to (e.message ?: "Nieznany błąd podczas synchronizacji")))
        }
    }

    private data class MediaFileInfo(
        val uri: Uri,
        val filePath: String,
        val displayName: String,
        val size: Long,
        val dateAdded: Long,
        val md5: String? = null
    )

    private fun scanDCIM(): List<MediaFileInfo> {
        val filesList = mutableListOf<MediaFileInfo>()
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )

        // Select only files from DCIM folder
        val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("%/DCIM/%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC" // oldest first

        val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        applicationContext.contentResolver.query(
            queryUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(dataColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val date = cursor.getLong(dateColumn)

                val contentUri = ContentUris.withAppendedId(queryUri, id)
                filesList.add(MediaFileInfo(contentUri, path, name, size, date))
            }
        }

        return filesList
    }

    private fun computeMD5ForUri(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val digest = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
                val md5sum = digest.digest()
                val bigInt = BigInteger(1, md5sum)
                String.format("%32s", bigInt.toString(16)).replace(' ', '0')
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun createTempFileFromUri(uri: Uri, fileName: String): File? {
        return try {
            val tempFile = File(applicationContext.cacheDir, "upload_$fileName")
            val inputStream = applicationContext.contentResolver.openInputStream(uri) ?: return null
            val outputStream = FileOutputStream(tempFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
