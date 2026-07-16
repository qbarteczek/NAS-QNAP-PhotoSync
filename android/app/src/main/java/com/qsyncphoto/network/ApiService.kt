package com.qsyncphoto.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class PairResult(val token: String, val deviceId: String)

    /**
     * Pairs the phone with the QNAP server using the 6-character code.
     */
    suspend fun pairDevice(serverUrl: String, code: String, deviceName: String): PairResult = withContext(Dispatchers.IO) {
        val url = "$serverUrl/api/auth/pair"
        val json = JSONObject().apply {
            put("code", code)
            put("deviceName", deviceName)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Nieudane parowanie: Kod błędu ${response.code}")
            }
            val bodyStr = response.body?.string() ?: throw IOException("Pusta odpowiedź serwera")
            val jsonResponse = JSONObject(bodyStr)
            PairResult(
                token = jsonResponse.getString("token"),
                deviceId = jsonResponse.getString("deviceId")
            )
        }
    }

    /**
     * Checks which file MD5s have already been successfully uploaded to the QNAP server.
     * Returns a list of MD5 hashes that are already synced.
     */
    suspend fun checkAlreadySynced(serverUrl: String, token: String, md5s: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (md5s.isEmpty()) return@withContext emptyList<String>()
        
        val url = "$serverUrl/api/sync-check"
        val jsonArray = JSONArray(md5s)
        val json = JSONObject().apply {
            put("md5s", jsonArray)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Błąd sprawdzania statusu: Kod ${response.code}")
            }
            val bodyStr = response.body?.string() ?: throw IOException("Pusta odpowiedź")
            val jsonResponse = JSONObject(bodyStr)
            val syncedArray = jsonResponse.getJSONArray("synced")
            val result = mutableListOf<String>()
            for (i in 0 until syncedArray.length()) {
                result.add(syncedArray.getString(i))
            }
            result
        }
    }

    /**
     * Uploads a single file to QNAP server with MD5 verification header.
     */
    suspend fun uploadFile(
        serverUrl: String,
        token: String,
        file: File,
        md5: String,
        creationDateIso: String
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "$serverUrl/api/upload"
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "photo",
                file.name,
                file.asRequestBody("image/*".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-File-MD5", md5)
            .header("X-File-Date", creationDateIso)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorMsg = response.body?.string() ?: ""
                throw IOException("Błąd wysyłania pliku: ${response.code} $errorMsg")
            }
            val bodyStr = response.body?.string() ?: ""
            val jsonResponse = JSONObject(bodyStr)
            jsonResponse.optBoolean("success", false)
        }
    }
}
