package com.homedesk.chat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object FieldShapes {
    val card = RoundedCornerShape(20.dp)
    val chip = RoundedCornerShape(100.dp)
    val button = RoundedCornerShape(14.dp)
    val search = RoundedCornerShape(16.dp)
    val bottomSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

object FieldMetrics {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space24 = 24.dp
    val minTouch = 48.dp
    val primaryTouch = 56.dp
    val screenPadding = 16.dp
}
