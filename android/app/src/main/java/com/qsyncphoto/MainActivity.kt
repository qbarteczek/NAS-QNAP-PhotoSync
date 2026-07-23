package com.qsyncphoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import androidx.work.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.qsyncphoto.data.AppDatabase
import com.qsyncphoto.network.ApiService
import com.qsyncphoto.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val internetGranted = permissions[Manifest.permission.INTERNET] ?: true
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        }
        
        if (!storageGranted) {
            Toast.makeText(this, "Wymagane uprawnienie do odczytu zdjęć w celu synchronizacji!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1), // Matches Web Accent Indigo
                    secondary = Color(0xFF06B6D4), // Matches Web Accent Cyan
                    background = Color(0xFF0D0F19), // Matches Web Dark Background
                    surface = Color(0xFF151829),
                    onBackground = Color(0xFFF1F3F9),
                    onSurface = Color(0xFFF1F3F9)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.syncedFileDao() }
    
    val sharedPrefs = remember { context.getSharedPreferences("qsyncphoto_prefs", Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(sharedPrefs.getString("server_url", "") ?: "") }
    var deviceToken by remember { mutableStateOf(sharedPrefs.getString("device_token", "") ?: "") }
    var deviceName by remember { mutableStateOf(sharedPrefs.getString("device_name", "") ?: "") }

    val isPaired = deviceToken.isNotEmpty()
    
    // Count of synced files
    val syncedCount by dao.getCount().collectAsState(initial = 0)

    // WorkManager Active Job Observer
    val workInfos = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData("qsyncphoto_sync_job")
        .asFlow()
        .collectAsState(initial = emptyList())

    val activeWorkInfo = workInfos.value.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val syncProgress = activeWorkInfo?.progress?.getInt("progress", -1) ?: -1
    val currentFileName = activeWorkInfo?.progress?.getString("currentFile") ?: ""

    var showQrScanner by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onQrScanned = { payload ->
                showQrScanner = false
                try {
                    val json = JSONObject(payload)
                    val url = json.getString("url")
                    val code = json.getString("code")
                    
                    // Attempt pairing
                    coroutineScope.launch {
                        pairWithServer(context, url, code, sharedPrefs) { token, name, resolvedUrl ->
                            deviceToken = token
                            deviceName = name
                            serverUrl = resolvedUrl
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Błędny kod QR parowania: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // App Title
        Text(
            text = "NAS QNAP Photo Sync",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        Text(
            text = "QNAP NAS Photo Synchronizer",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        if (!isPaired) {
            // Pairing Interface
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Parowanie urządzenia",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    var inputUrl by remember { mutableStateOf("") }
                    var inputCode by remember { mutableStateOf("") }
                    var inputName by remember { mutableStateOf(Build.MODEL) }

                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("Nazwa telefonu") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showQrScanner = true
                            } else {
                                Toast.makeText(context, "Zezwól na aparat w ustawieniach telefonu, aby skanować QR!", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        Text("Zeskanuj kod QR z QNAP")
                    }

                    HorizontalDivider(modifier = Modifier.padding(bottom = 20.dp))

                    Text(
                        text = "Lub skonfiguruj ręcznie:",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        label = { Text("Adres serwera (np. http://192.168.1.100:3000)") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it },
                        label = { Text("Kod parowania (6 znaków)") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    )

                    Button(
                        onClick = {
                            if (inputUrl.isEmpty() || inputCode.isEmpty()) {
                                Toast.makeText(context, "Wypełnij wszystkie pola!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            coroutineScope.launch {
                                pairWithServer(context, inputUrl, inputCode, sharedPrefs) { token, name, resolvedUrl ->
                                    deviceToken = token
                                    deviceName = name
                                    serverUrl = resolvedUrl
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Połącz z serwerem")
                    }
                }
            }
        } else {
            // Dashboard Interface
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Status połączenia",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text("Urządzenie: $deviceName", fontWeight = FontWeight.Medium)
                    Text("Serwer: $serverUrl", fontSize = 13.sp, color = Color.Gray)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Zsynchronizowane pliki: $syncedCount", fontWeight = FontWeight.Bold)
                }
            }

            // Sync actions
            Button(
                onClick = { triggerSyncNow(context) },
                enabled = activeWorkInfo == null,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                Text(if (activeWorkInfo != null) "Synchronizacja w toku..." else "Synchronizuj teraz")
            }

            // Active Sync Progress
            AnimatedVisibility(visible = activeWorkInfo != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Trwa synchronizacja...", fontWeight = FontWeight.SemiBold)
                        if (currentFileName.isNotEmpty()) {
                            Text("Plik: $currentFileName", fontSize = 12.sp, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (syncProgress >= 0) {
                            LinearProgressIndicator(
                                progress = { syncProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$syncProgress%", fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // Options & Auto Sync Settings
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Ustawienia synchronizacji w tle",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    var wifiOnly by remember { mutableStateOf(sharedPrefs.getBoolean("wifi_only", true)) }
                    var chargingOnly by remember { mutableStateOf(sharedPrefs.getBoolean("charging_only", true)) }
                    var autoSyncEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_sync", false)) }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tylko przez Wi-Fi", fontWeight = FontWeight.Medium)
                            Text("Zapobiega użyciu transferu GSM", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                sharedPrefs.edit().putBoolean("wifi_only", it).apply()
                                if (autoSyncEnabled) setupAutoSync(context, wifiOnly, chargingOnly)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tylko podczas ładowania", fontWeight = FontWeight.Medium)
                            Text("Chroni żywotność baterii", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = chargingOnly,
                            onCheckedChange = {
                                chargingOnly = it
                                sharedPrefs.edit().putBoolean("charging_only", it).apply()
                                if (autoSyncEnabled) setupAutoSync(context, wifiOnly, chargingOnly)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Automatyczny harmonogram w tle", fontWeight = FontWeight.Medium)
                            Text("Co 2 godziny w tle", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = {
                                autoSyncEnabled = it
                                sharedPrefs.edit().putBoolean("auto_sync", it).apply()
                                if (it) {
                                    setupAutoSync(context, wifiOnly, chargingOnly)
                                    Toast.makeText(context, "Uruchomiono harmonogram w tle!", Toast.LENGTH_SHORT).show()
                                } else {
                                    cancelAutoSync(context)
                                    Toast.makeText(context, "Wyłączono harmonogram w tle.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            // Unpair Button
            Button(
                onClick = {
                    sharedPrefs.edit().clear().apply()
                    deviceToken = ""
                    deviceName = ""
                    serverUrl = ""
                    cancelAutoSync(context)
                    coroutineScope.launch {
                        dao.clearAll()
                    }
                    Toast.makeText(context, "Wyrejestrowano urządzenie.", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Odłącz urządzenie (Wyrejestruj)")
            }
        }
    }
}

private suspend fun pairWithServer(
    context: Context,
    url: String,
    code: String,
    sharedPrefs: SharedPreferences,
    onSuccess: (token: String, name: String, resolvedUrl: String) -> Unit
) {
    val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
        "http://$url"
    } else {
        url
    }.trimEnd('/')

    val name = Build.MODEL

    try {
        val apiService = ApiService()
        val result = apiService.pairDevice(cleanUrl, code, name)
        
        sharedPrefs.edit().apply {
            putString("server_url", cleanUrl)
            putString("device_token", result.token)
            putString("device_id", result.deviceId)
            putString("device_name", name)
        }.apply()

        onSuccess(result.token, name, cleanUrl)
        Toast.makeText(context, "Połączono pomyślnie z QNAP!", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Błąd łączenia: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun triggerSyncNow(context: Context) {
    val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    
    WorkManager.getInstance(context).enqueueUniqueWork(
        "qsyncphoto_sync_job",
        ExistingWorkPolicy.KEEP,
        workRequest
    )
    Toast.makeText(context, "Rozpoczęto synchronizację...", Toast.LENGTH_SHORT).show()
}

private fun setupAutoSync(context: Context, wifiOnly: Boolean, chargingOnly: Boolean) {
    val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
    
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(networkType)
        .setRequiresCharging(chargingOnly)
        .build()

    val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "qsyncphoto_auto_sync",
        ExistingPeriodicWorkPolicy.UPDATE,
        periodicWorkRequest
    )
}

private fun cancelAutoSync(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork("qsyncphoto_auto_sync")
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerDialog(onDismiss: () -> Unit, onQrScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Zeskanuj kod parowania QR",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().apply {
                                    setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                                val barcodeScanner = BarcodeScanning.getClient()
                                val analysis = ImageAnalysis.Builder().build().apply {
                                    setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            barcodeScanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    barcodes.firstOrNull()?.rawValue?.let { raw ->
                                                        onQrScanned(raw)
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }
                                }
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Anuluj")
                }
            }
        }
    }
}
