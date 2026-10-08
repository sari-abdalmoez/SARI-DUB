package com.saridub.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0A0A0F)
val CardC = Color(0xFF1A1A24)
val Card2 = Color(0xFF252534)
val Accent1 = Color(0xFF8B6CFF)
val Accent2 = Color(0xFF3AA8FF)
val Dim = Color(0xFF9A9AB0)
val Ok = Color(0xFF4CD08A)
val Bad = Color(0xFFFF6B6B)

@Composable
fun SariTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent1, secondary = Accent2, background = Bg, surface = CardC,
        onSurface = Color.White, onBackground = Color.White, onPrimary = Color.White
    ), content = content
)

private val highlight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent))

/** Gradient, raised button with inner top highlight and a light haptic tick. */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val haptic = LocalHapticFeedback.current
    val brush = if (enabled) Brush.horizontalGradient(listOf(Accent1, Accent2)) else Brush.horizontalGradient(listOf(Card2, Card2))
    Box(
        modifier.clip(shape).background(brush).border(BorderStroke(1.dp, highlight), shape)
            .clickable(enabled = enabled) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else Dim, fontSize = 15.sp) }
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (selected) Accent1.copy(alpha = 0.85f) else Card2)
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, fontSize = 13.sp, color = Color.White, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
fun SCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(CardC).border(BorderStroke(1.dp, highlight), shape).padding(16.dp),
        content = content
    )
}
