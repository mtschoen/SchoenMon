package com.example.perfstream.ui.xr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import com.example.perfstream.MainNavigation

/**
 * Single content entry point.
 *
 * - Spatial UI enabled (Galaxy XR in full space) -> [SpatialDashboard].
 * - Otherwise (phones, the Folds, or XR while in 2D home space) -> the existing
 *   flat [MainNavigation], unchanged.
 *
 * The gate is [LocalSpatialCapabilities].current.isSpatialUiEnabled (package
 * androidx.xr.compose.platform), the same check the official android/xr-samples
 * hybrid app uses. It is correct in both XR modes: false in home space, true in
 * full space, so promoting to full space flips it to [SpatialDashboard] with no
 * extra wiring.
 */
@Composable
fun PerfStreamRoot() {
    if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        SpatialDashboard()
    } else {
        FlatRoot()
    }
}

/**
 * The flat dashboard. On an XR device in home space it carries an "Enter full
 * space" control so the spatial layout is reachable without hunting for system
 * chrome. On phones/Folds `hasXrSpatialFeature` is false, so the control never
 * appears and the flat UI is byte-for-byte unchanged.
 */
@Composable
private fun FlatRoot() {
    val configuration = LocalSpatialConfiguration.current
    if (configuration.hasXrSpatialFeature) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Reserve a bottom strip so the scrollable dashboard ends above the
            // floating control: scrolled all the way down, the storage row stops
            // short of the button instead of bumping it. XR-only - phones never
            // enter this branch, so their layout is unchanged.
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                MainNavigation()
            }
            // Bottom-right, FAB-style: clears the dashboard header (the LIVE/
            // STOPPED pill lives top-right) so the two never collide.
            FilledTonalButton(
                onClick = { configuration.requestFullSpaceMode() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            ) {
                Text("Enter full space")
            }
        }
    } else {
        MainNavigation()
    }
}
