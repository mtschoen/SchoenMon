package com.sticktoitive.schoenmon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.sticktoitive.schoenmon.service.PerformanceMonitorService
import com.sticktoitive.schoenmon.theme.SchoenMonTheme
import com.sticktoitive.schoenmon.ui.xr.SchoenMonRoot

class MainActivity : ComponentActivity() {

  // Auto-request permission at first launch and start service immediately once allowed
  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    if (isGranted) {
      PerformanceMonitorService.startService(this)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Check and trigger auto-start logic
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
      if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
        PerformanceMonitorService.startService(this)
      } else {
        // Request notification permission immediately on first launch to unlock status bar icon
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    } else {
      PerformanceMonitorService.startService(this)
    }

    enableEdgeToEdge()
    setContent {
      SchoenMonTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { SchoenMonRoot() } }
    }
  }
}
