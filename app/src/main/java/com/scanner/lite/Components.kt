package com.scanner.lite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// 半透明圆形图标按钮
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = description)
    }
}

// 拍照按钮：白色圆环 + 白色圆心
@Composable
fun CaptureButton(onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
    )
}

// 扫码 / OCR 切换
@Composable
fun ModeSwitch(isScanMode: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.4f)) {
        Row(Modifier.padding(4.dp)) {
            ModeItem("扫码", isScanMode) { onChange(true) }
            ModeItem("OCR", !isScanMode) { onChange(false) }
        }
    }
}

@Composable
fun ModeItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}