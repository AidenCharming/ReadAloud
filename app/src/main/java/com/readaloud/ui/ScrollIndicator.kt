package com.readaloud.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a thin scroll bar down the right edge of a lazy list, always visible, so a short list in a dialog
 * shows that there is more to scroll to. Nothing is drawn when every item already fits.
 */
fun Modifier.scrollIndicator(state: LazyListState, color: Color, thickness: Dp = 4.dp): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible == 0 || visible >= total) return@drawWithContent
    val track = size.height
    val thumb = (track * visible / total).coerceAtLeast(28.dp.toPx())
    val progress = (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    val width = thickness.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width, progress * (track - thumb)),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2),
    )
}
