package dev.tablettrackpad.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's slider: a slim rounded track filled up to the value and a round thumb, in place of
 * Material 3's thick split track and bar-shaped thumb. Dragging, keys and accessibility are
 * Material's own. With [trackBrush] the track shows that gradient instead of a fill, for picking
 * colors, and the thumb turns white so it stands out on any hue.
 */
@Composable
fun TrackpadSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    trackBrush: Brush? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val colors = MaterialTheme.colorScheme
    val thumbFill = if (trackBrush != null) Color.White else colors.primary
    val thumbRing = if (trackBrush != null) colors.outline else colors.surfaceContainerLowest
    val filled = colors.primary
    val empty = colors.primary.copy(alpha = 0.18f)
    // The caller owns the value: the state just mirrors it, and drags report through onValueChange.
    val state = remember(valueRange) { SliderState(value, trackRange = valueRange) }
    state.value = value
    Slider(
        state = state,
        onValueChange = onValueChange,
        modifier = modifier,
        interactionSource = interaction,
        thumb = {
            val diameter by animateDpAsState(
                if (pressed || dragged) THUMB_PRESSED else THUMB,
                MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "thumb",
            )
            // A fixed footprint, so the track keeps the same length while the thumb grows.
            Box(Modifier.size(THUMB_PRESSED), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(diameter)
                        .shadow(2.dp, CircleShape)
                        .background(thumbFill, CircleShape)
                        .border(3.dp, thumbRing, CircleShape),
                )
            }
        },
        track = { current ->
            val start = current.trackRange.start
            val span = current.trackRange.endInclusive - start
            val fraction = if (span > 0f) ((current.value - start) / span).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(if (trackBrush != null) 12.dp else 6.dp)) {
                val corners = CornerRadius(size.height / 2)
                if (trackBrush != null) {
                    drawRoundRect(trackBrush, cornerRadius = corners)
                } else {
                    drawRoundRect(empty, cornerRadius = corners)
                    drawRoundRect(filled, size = Size(size.width * fraction, size.height), cornerRadius = corners)
                }
            }
        },
    )
}

private val THUMB = 22.dp
private val THUMB_PRESSED = 26.dp
