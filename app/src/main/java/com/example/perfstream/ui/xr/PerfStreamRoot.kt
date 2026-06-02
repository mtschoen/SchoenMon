package com.example.perfstream.ui.xr

import androidx.compose.runtime.Composable
import androidx.xr.compose.platform.LocalSpatialCapabilities
import com.example.perfstream.MainNavigation

/**
 * Single content entry point. On a spatial-capable context (Galaxy XR with
 * spatial UI enabled) it renders the spatial layout; everywhere else - phones,
 * the Folds, and XR while in 2D home space - it renders the existing flat app,
 * completely unchanged.
 *
 * The gate is [LocalSpatialCapabilities].current.isSpatialUiEnabled (package
 * androidx.xr.compose.platform), the same check the official android/xr-samples
 * hybrid app uses. It is correct in both XR modes: false in home space (flat
 * panel) and true in full space (spatial layout), so promoting the app to full
 * space on the headset flips it to [SpatialDashboard] with no extra wiring.
 */
@Composable
fun PerfStreamRoot() {
    if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        SpatialDashboard()
    } else {
        MainNavigation()
    }
}
