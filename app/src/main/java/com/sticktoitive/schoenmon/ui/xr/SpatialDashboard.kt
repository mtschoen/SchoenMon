package com.sticktoitive.schoenmon.ui.xr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.transformingMovable
import androidx.xr.compose.subspace.layout.width
import com.sticktoitive.schoenmon.ui.dashboard.DashboardScreen

/**
 * Galaxy XR layout. v1 = the existing flat dashboard floating as a grabbable,
 * resizable [SpatialPanel]. Phase B adds the per-core ridgeline surface beside
 * it in this same [Subspace].
 *
 * `transformingMovable().resizable()` is the grab-and-resize modifier pair the
 * official android/xr-samples app uses on its panels. The hosted content is the
 * untouched flat [DashboardScreen] - one telemetry stack, rendered flat on
 * phones and inside this panel on XR.
 */
@Composable
fun SpatialDashboard() {
    Subspace {
        SpatialPanel(
            modifier = SubspaceModifier
                .width(640.dp)
                .height(800.dp)
                .transformingMovable()
                .resizable()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                DashboardScreen(modifier = Modifier.fillMaxSize())
            }
        }
        // The ridgeline mesh, wrapped in its own grabbable/resizable volume beside
        // the panel. A subspace composable, so it lives inside the Subspace DSL.
        RidgelineSurface()
    }
}
