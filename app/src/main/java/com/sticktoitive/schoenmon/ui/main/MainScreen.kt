package com.sticktoitive.schoenmon.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import com.sticktoitive.schoenmon.ui.dashboard.DashboardScreen

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  DashboardScreen(modifier = modifier)
}
