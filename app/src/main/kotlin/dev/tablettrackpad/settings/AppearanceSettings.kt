package dev.tablettrackpad.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/**
 * Colors and opacities of the overlays. A color several parts share gets one row of its own,
 * ahead of those parts' opacity sliders.
 */
@Composable
internal fun AppearanceSection(revision: SettingsRevision) {
    val settings = revision.settings
    // Saved, like the picker's own state, so a rotation keeps the dialog open where it was.
    var editing by rememberSaveable { mutableStateOf<Pair<OverlayColor, String>?>(null) }
    Section("Appearance") {
        Hint("Tap a swatch to change a color. The overlays update as you go.")
        var group: String? = null
        val colorRowsShown = HashSet<OverlayColor>()
        for (part in OverlayPart.entries) {
            if (part.group != group) {
                group = part.group
                Text(
                    part.group,
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val color = part.color
            val sharers = OverlayPart.entries.filter { it.color == color }
            val shared = color != null && sharers.size > 1
            if (shared && colorRowsShown.add(color)) {
                val names = sharers.joinToString { it.label.lowercase() }.replaceFirstChar { it.uppercase() }
                val title = "${part.group} color"
                SharedColorRow(argb(settings.color(color), 1f), title, names) { editing = color to title }
            }
            // Rows get values rather than the settings, so each change recomposes them.
            val ownColor = color.takeIf { !shared }
            OpacityRow(
                label = part.label,
                description = "${part.group} ${part.label} color",
                opacity = settings.opacity(part),
                swatch = ownColor?.let { settings.argb(part) },
                onOpacity = { settings.setOpacity(part, it) },
                onEditColor = { ownColor?.let { editing = it to "${part.group}: ${part.label}" } },
            )
        }
        TextButton(shapes = ButtonDefaults.shapes(), onClick = { settings.resetAppearance() }) { Text("Reset colors and opacity") }
    }
    editing?.let { (color, title) -> ColorPickerDialog(settings, color, title) { editing = null } }
}

@Composable
private fun SharedColorRow(argb: Int, title: String, usedBy: String, onEdit: () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(argb, SWATCH_SIZE, title, onEdit)
        Spacer(Modifier.width(SWATCH_GAP))
        Column {
            Text("Color", style = MaterialTheme.typography.bodyLarge)
            Hint(usedBy)
        }
    }
}

/** An opacity slider, with a [swatch] (ARGB) when the part has a color of its own. */
@Composable
private fun OpacityRow(
    label: String,
    description: String,
    opacity: Float,
    swatch: Int?,
    onOpacity: (Float) -> Unit,
    onEditColor: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (swatch != null) {
            Swatch(swatch, SWATCH_SIZE, description, onEditColor)
        } else {
            Spacer(Modifier.width(SWATCH_SIZE))
        }
        Spacer(Modifier.width(SWATCH_GAP))
        Column(Modifier.weight(1f)) {
            Row {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${(opacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TrackpadSlider(value = opacity, onValueChange = onOpacity)
        }
    }
}

/**
 * Edits [color]. Changes are saved as they are made, so the overlays show them live; Cancel puts
 * the old color back.
 */
@Composable
private fun ColorPickerDialog(settings: TrackpadSettings, color: OverlayColor, title: String, onClose: () -> Unit) {
    // Saved so that Cancel still restores the color from before the dialog opened after a rotation.
    val original = rememberSaveable { settings.color(color) }
    val hsv = FloatArray(3).also { AndroidColor.colorToHSV(original, it) }
    var hue by rememberSaveable { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(hsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(hsv[2]) }
    var hex by rememberSaveable { mutableStateOf(hexOf(original)) }

    fun rgb() = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF

    fun apply(rgb: Int, updateHex: Boolean = true) {
        settings.setColor(color, rgb)
        if (updateHex) hex = hexOf(rgb)
    }

    fun pick(rgb: Int, updateHex: Boolean = true) {
        val values = FloatArray(3)
        AndroidColor.colorToHSV(rgb, values)
        hue = values[0]
        saturation = values[1]
        brightness = values[2]
        apply(rgb, updateHex)
    }

    val cancel = {
        settings.setColor(color, original)
        onClose()
    }
    AlertDialog(
        onDismissRequest = cancel,
        confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = cancel) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(argb(rgb(), 1f), 56.dp, "Preview", onClick = null)
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            val clean = text.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hex = clean
                            clean.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { pick(it, updateHex = false) }
                        },
                        prefix = { Text("#") },
                        label = { Text("Hex") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.width(160.dp),
                    )
                }
                val presets = listOf(MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF) + PRESETS
                for (row in presets.chunked(PRESETS_PER_ROW)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in row) {
                            Swatch(argb(preset, 1f), 32.dp, "Preset #${hexOf(preset)}", onClick = { pick(preset) })
                        }
                    }
                }
                LabeledGradientSlider("Hue", hue, 0f..360f, Brush.horizontalGradient(HUES)) {
                    hue = it
                    apply(rgb())
                }
                LabeledGradientSlider(
                    "Saturation",
                    saturation,
                    0f..1f,
                    Brush.horizontalGradient(listOf(hsvColor(hue, 0f, brightness), hsvColor(hue, 1f, brightness))),
                ) {
                    saturation = it
                    apply(rgb())
                }
                LabeledGradientSlider(
                    "Brightness",
                    brightness,
                    0f..1f,
                    Brush.horizontalGradient(listOf(Color.Black, hsvColor(hue, saturation, 1f))),
                ) {
                    brightness = it
                    apply(rgb())
                }
            }
        },
    )
}

@Composable
private fun LabeledGradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TrackpadSlider(value = value, onValueChange = onChange, valueRange = range, trackBrush = brush)
    }
}

/** A round color sample over a checkerboard, so transparency shows. */
@Composable
private fun Swatch(argb: Int, size: Dp, description: String, onClick: (() -> Unit)?) {
    val rim = MaterialTheme.colorScheme.outline
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(click)
            .semantics { contentDescription = description }
            .checkerboard(),
    ) {
        Canvas(Modifier.size(size)) {
            drawRect(Color(argb))
            drawCircle(rim, radius = this.size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
    }
}

/** White and light-gray squares behind a color, so its transparency shows. */
private fun Modifier.checkerboard(): Modifier = drawBehind {
    drawRect(Color.White)
    val cell = 6.dp.toPx()
    val columns = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if ((row + column) % 2 == 0) drawRect(CHECKER, Offset(column * cell, row * cell), Size(cell, cell))
        }
    }
}

private fun hsvColor(hue: Float, saturation: Float, brightness: Float) =
    Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))

private fun hexOf(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)

private val CHECKER = Color(0xFFCCCCCC)
private val SWATCH_SIZE = 40.dp
private val SWATCH_GAP = 16.dp
private val HUES = (0..6).map { hsvColor(it * 60f, 1f, 1f) }
private const val PRESETS_PER_ROW = 8

/** Neutrals, then a spectrum: with the theme's own accent in front, two rows of eight. */
private val PRESETS = listOf(
    0xFFFFFF, 0xC7C7CC, 0x8E8E93, 0x3A3A3C, 0x1C1C1E, 0x000000,
    0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x30B0C7, 0x3D8BFF, 0x5856D6, 0xAF52DE, 0xFF2D55,
)
