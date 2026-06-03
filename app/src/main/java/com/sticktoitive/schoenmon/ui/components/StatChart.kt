package com.sticktoitive.schoenmon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun StatChart(
    data: List<Float>, // Values normalized between 0f and 1f (representing history)
    lineColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F0F15))
    ) {
        val width = size.width
        val height = size.height
        
        // Draw standard subtle grid lines behind
        val gridLines = 3
        for (i in 1..gridLines) {
            val y = (height / (gridLines + 1)) * i
            drawLine(
                color = Color(0xFF2C2C35).copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 2f
            )
        }

        if (data.size < 2) return@Canvas
        
        val stepX = width / (data.size - 1)
        val path = Path()
        val fillPath = Path()
        
        // Start points
        val startY = height - (data[0].coerceIn(0f, 1f) * height)
        path.moveTo(0f, startY)
        fillPath.moveTo(0f, height)
        fillPath.lineTo(0f, startY)
        
        for (i in 1 until data.size) {
            val currentX = i * stepX
            val currentY = height - (data[i].coerceIn(0f, 1f) * height)
            
            val prevX = (i - 1) * stepX
            val prevY = height - (data[i - 1].coerceIn(0f, 1f) * height)
            
            // Smooth Bézier curve calculation
            val controlX1 = prevX + (stepX / 2f)
            val controlY1 = prevY
            val controlX2 = prevX + (stepX / 2f)
            val controlY2 = currentY
            
            path.cubicTo(controlX1, controlY1, controlX2, controlY2, currentX, currentY)
            fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, currentX, currentY)
        }
        
        fillPath.lineTo(width, height)
        fillPath.close()
        
        // Draw gradient area underneath the line
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(fillColor.copy(alpha = 0.35f), Color.Transparent),
                startY = 0f,
                endY = height
            )
        )
        
        // Draw the smooth line on top
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4.5f)
        )
    }
}
