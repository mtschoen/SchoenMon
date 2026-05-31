package com.example.perfstream.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.perfstream.core.PerformanceStats
import com.example.perfstream.data.PerformanceMonitorRepository
import com.example.perfstream.service.PerformanceMonitorService
import com.example.perfstream.ui.components.StatChart
import java.util.Locale

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stats by PerformanceMonitorRepository.stats.collectAsStateWithLifecycle(null)
    val history by PerformanceMonitorRepository.history.collectAsStateWithLifecycle(emptyList())

    var isServiceRunning by remember { mutableStateOf(false) }

    // Check if background service is running
    LaunchedEffect(stats) {
        isServiceRunning = stats != null
    }

    // Permission launcher for Android 13+ Notifications
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            PerformanceMonitorService.startService(context)
            isServiceRunning = true
        }
    }

    fun toggleService() {
        if (isServiceRunning) {
            PerformanceMonitorService.stopService(context)
            isServiceRunning = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PerformanceMonitorService.startService(context)
                isServiceRunning = true
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF08080C))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App header
        HeaderSection(isServiceRunning)

        Spacer(modifier = Modifier.height(16.dp))

        // Service controller card
        ServiceController(isServiceRunning, ::toggleService)

        Spacer(modifier = Modifier.height(20.dp))

        // Stats UI
        if (stats == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Click 'START SERVICE' to begin monitoring...",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            val currentStats = stats!!
            
            // CPU Section
            CpuMetricCard(currentStats, history)

            Spacer(modifier = Modifier.height(16.dp))

            // Network Section
            NetworkMetricCard(currentStats, history)

            Spacer(modifier = Modifier.height(16.dp))

            // RAM Section
            MemoryMetricCard(currentStats, history)

            Spacer(modifier = Modifier.height(16.dp))

            // Storage Section
            DiskMetricCard(currentStats)
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun HeaderSection(isServiceRunning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "PERFSTREAM",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.5.sp,
                color = Color.White
            )
            Text(
                text = "Hardware Performance Hub",
                fontSize = 12.sp,
                color = Color.Gray,
                letterSpacing = 0.5.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF12121A))
                .padding(horizontal = 12.dp, java.lang.Float.max(6f, 6f).dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(if (isServiceRunning) pulseAlpha else 1f)
                    .background(if (isServiceRunning) Color(0xFF00E676) else Color.Red)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isServiceRunning) "LIVE" else "STOPPED",
                color = if (isServiceRunning) Color(0xFF00E676) else Color.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun ServiceController(isRunning: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Brush.horizontalGradient(
                    colors = if (isRunning) {
                        listOf(Color(0xFF00E5FF).copy(alpha = 0.3f), Color(0xFF00E676).copy(alpha = 0.3f))
                    } else {
                        listOf(Color(0xFF2C2C35), Color(0xFF2C2C35))
                    }
                ),
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101016)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Background Monitor",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = if (isRunning) "Service is active in status bar." else "Service inactive. Tap to start.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFD50000) else Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (isRunning) "STOP SERVICE" else "START SERVICE",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    fontSize = 12.sp,
                    color = if (isRunning) Color.White else Color.Black
                )
            }
        }
    }
}

@Composable
fun CpuMetricCard(stats: PerformanceStats, history: List<PerformanceStats>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12121A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CPU FREQUENCY LOAD",
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format(Locale.US, "%.0f%%", stats.avgCpuFrequencyPercent),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Custom Linear Progress bar
            LinearProgressIndicator(
                progress = { stats.avgCpuFrequencyPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFF00E5FF),
                trackColor = Color(0xFF1E1E28)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Cores Speeds List
            Text(
                text = "Core Clock Speeds",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val freqs = stats.cpuCoreFrequencies
                val maxs = stats.cpuMaxFreqs
                
                Column(modifier = Modifier.weight(1f)) {
                    for (i in 0 until (freqs.size / 2).coerceAtLeast(1)) {
                        if (i < freqs.size) {
                            val activeFreq = freqs[i]
                            val maxFreq = if (i < maxs.size) maxs[i] else 0L
                            Text(
                                text = "Core $i: ${activeFreq}MHz / ${maxFreq}MHz",
                                color = if (activeFreq > 0) Color.White else Color.DarkGray,
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    for (i in (freqs.size / 2).coerceAtLeast(1) until freqs.size) {
                        val activeFreq = freqs[i]
                        val maxFreq = if (i < maxs.size) maxs[i] else 0L
                        Text(
                            text = "Core $i: ${activeFreq}MHz / ${maxFreq}MHz",
                            color = if (activeFreq > 0) Color.White else Color.DarkGray,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chart
            val chartData = history.map { it.avgCpuFrequencyPercent / 100f }
            StatChart(
                data = chartData,
                lineColor = Color(0xFF00E5FF),
                fillColor = Color(0xFF00E5FF),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun NetworkMetricCard(stats: PerformanceStats, history: List<PerformanceStats>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12121A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "NETWORK BANDWIDTH",
                color = Color(0xFF00E676),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "DOWNLOAD", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = formatBandwidth(stats.rxSpeedBytesPerSec),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "UPLOAD", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = formatBandwidth(stats.txSpeedBytesPerSec),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speed history normalized to local peak
            val speeds = history.map { (it.rxSpeedBytesPerSec + it.txSpeedBytesPerSec).toFloat() }
            val peakSpeed = speeds.maxOrNull()?.coerceAtLeast(1024f) ?: 1024f
            val chartData = speeds.map { it / peakSpeed }

            StatChart(
                data = chartData,
                lineColor = Color(0xFF00E676),
                fillColor = Color(0xFF00E676),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun MemoryMetricCard(stats: PerformanceStats, history: List<PerformanceStats>) {
    val usedGb = stats.usedMemoryBytes / 1024f / 1024f / 1024f
    val totalGb = stats.totalMemoryBytes / 1024f / 1024f / 1024f
    val ramPercent = (stats.usedMemoryBytes.toFloat() / stats.totalMemoryBytes.toFloat()) * 100f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12121A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MEMORY UTILIZATION",
                    color = Color(0xFFD500F9),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format(Locale.US, "%.1fG / %.1fG", usedGb, totalGb),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { ramPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFFD500F9),
                trackColor = Color(0xFF1E1E28)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Chart
            val chartData = history.map { (it.usedMemoryBytes.toFloat() / it.totalMemoryBytes.toFloat()) }
            StatChart(
                data = chartData,
                lineColor = Color(0xFFD500F9),
                fillColor = Color(0xFFD500F9),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun DiskMetricCard(stats: PerformanceStats) {
    val usedGb = stats.usedDiskBytes / 1024f / 1024f / 1024f
    val totalGb = stats.totalDiskBytes / 1024f / 1024f / 1024f
    val diskPercent = (stats.usedDiskBytes.toFloat() / stats.totalDiskBytes.toFloat()) * 100f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12121A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STORAGE SPACE",
                    color = Color(0xFFFFAB00),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format(Locale.US, "%.1fG / %.1fG", usedGb, totalGb),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { diskPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFFFFAB00),
                trackColor = Color(0xFF1E1E28)
            )

            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = String.format(Locale.US, "%.0f%% Storage Occupied", diskPercent),
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}

private fun formatBandwidth(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / 1024f / 1024f)
        bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024f)
        else -> "$bytesPerSec B/s"
    }
}
