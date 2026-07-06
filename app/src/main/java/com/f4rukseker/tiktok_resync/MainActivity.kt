package com.f4rukseker.tiktok_resync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        hasAllFilesAccess = { hasAllFilesAccess() },
                        onRequestAccess = { requestAllFilesAccess() },
                        onStartService = { startMoverService() },
                        hasBatteryOptimizationExemption = { hasBatteryOptimizationExemption() },
                        onRequestBatteryOptimizationExemption = { requestBatteryOptimizationExemption() }
                    )
                }
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_CODE_STORAGE
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
    }

    private fun hasBatteryOptimizationExemption(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun startMoverService() {
        val serviceIntent = Intent(this, TikTokMoverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 100
        private const val REQUEST_CODE_NOTIFICATIONS = 101
    }
}

@Composable
private fun MainScreen(
    hasAllFilesAccess: () -> Boolean,
    onRequestAccess: () -> Unit,
    onStartService: () -> Unit,
    hasBatteryOptimizationExemption: () -> Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit
) {
    var statusText by remember { mutableStateOf("Checking permissions...") }
    var accessGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    var batteryExempt by remember { mutableStateOf(hasBatteryOptimizationExemption()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = if (accessGranted) {
                "All files access: granted. You can start the service."
            } else {
                "All files access: NOT granted. Tap the button below first."
            }
        )

        Button(onClick = {
            onRequestAccess()
        }) {
            Text("Grant all files access")
        }

        Text(
            text = if (batteryExempt) {
                "Battery optimization: exempt. The service is less likely to be killed."
            } else {
                "Battery optimization: NOT exempt. Some phones may kill the service."
            }
        )

        Button(onClick = {
            onRequestBatteryOptimizationExemption()
            batteryExempt = hasBatteryOptimizationExemption()
        }) {
            Text("Disable battery optimization")
        }

        Button(onClick = {
            accessGranted = hasAllFilesAccess()
            if (accessGranted) {
                onStartService()
                statusText = "Service started, watching DCIM/Camera."
            } else {
                statusText = "Grant all files access first."
            }
        }) {
            Text("Start TikTok Mover")
        }

        Text(text = statusText)
    }
}