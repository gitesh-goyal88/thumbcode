package `in`.thumbcode.receiver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import `in`.thumbcode.receiver.Frame
import `in`.thumbcode.receiver.Spec
import `in`.thumbcode.receiver.Stage
import kotlin.math.min

val Paper = Color(0xFF141513)
val Panel = Color(0xFF232421)
val Rule = Color(0xFF3A3B37)
val Dim = Color(0xFF8C8D86)
val Ink = Color(0xFFEDEDE8)
val Locked = Color(0xFF7FB2E8)
val SetBit = Color(0xFF63C6A0)
val SpotOn = Color(0xFFE8A33D)
val Good = Color(0xFF63C6A0)
val Bad = Color(0xFFE08A82)
val Warn = Color(0xFFE8C25A)

/**
 * Redraws the canonical 400 x 480 space from what this frame actually
 * measured. It doubles as the tuning instrument: if the ring reads as an arc
 * or the spots land off-anchor, you see it here rather than guessing from a
 * failure message.
 */
@Composable
fun CanvasOverlay(frame: Frame, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = min(size.width / Spec.W.toFloat(), size.height / Spec.H.toFloat())
        val ox = (size.width - Spec.W.toFloat() * s) / 2f
        val oy = (size.height - Spec.H.toFloat() * s) / 2f
        fun px(x: Double, y: Double) = Offset(ox + x.toFloat() * s, oy + y.toFloat() * s)

        drawRect(Panel, Offset(ox, oy), Size(Spec.W.toFloat() * s, Spec.H.toFloat() * s))

        val locked = frame.stage != Stage.SEARCHING
        for (f in Spec.FIDUCIALS) {
            val side = 26f * s
            drawRect(
                color = if (locked) Locked else Rule,
                topLeft = px(f[0] - 13.0, f[1] - 13.0),
                size = Size(side, side),
            )
        }

        drawOval(
            color = Rule,
            topLeft = px(Spec.CX - Spec.RX, Spec.CY - Spec.RY),
            size = Size((Spec.RX * 2).toFloat() * s, (Spec.RY * 2).toFloat() * s),
            style = Stroke(width = 1.2f * s),
        )

        val bits = frame.bits
        for (j in 0 until Spec.RING_SLOTS) {
            val p = Spec.slot(j)
            val set = bits != null && bits[j] == 1
            drawCircle(
                color = when {
                    bits == null -> Rule
                    set -> SetBit
                    else -> Dim
                },
                radius = (if (set) 4.2f else 1.6f) * s,
                center = px(p[0], p[1]),
            )
        }

        val spots = frame.spots
        for (i in 0 until Spec.ANCHOR_COUNT) {
            val a = Spec.ANCHORS[i]
            if (spots != null && i in spots) {
                drawCircle(SpotOn, 11f * s, px(a[0], a[1]))
            } else {
                drawCircle(Rule, 2.2f * s, px(a[0], a[1]), style = Stroke(width = 0.8f * s))
            }
        }

        val wedge = frame.wedge
        val shades = listOf(0xFF000000, 0xFF666666, 0xFFB3B3B3, 0xFFFFFFFF)
        for (i in 0 until 4) {
            val measured = wedge?.get(i)
            val color = if (measured != null) {
                val v = measured.toInt().coerceIn(0, 255)
                Color(v, v, v)
            } else {
                Color(shades[i])
            }
            drawRect(color, px(144.0 + 28 * i, 424.0), Size(28f * s, 16f * s))
        }
    }
}
