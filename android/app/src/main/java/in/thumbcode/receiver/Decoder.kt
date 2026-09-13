package `in`.thumbcode.receiver

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Stage { SEARCHING, CORNERS, WARPED, WEDGE, RING, SPOTS }

/**
 * Everything the decoder learned about one frame. The UI renders this directly,
 * so a stalled decode is legible instead of silent — which is also how you tune
 * the thresholds.
 */
data class Frame(
    val stage: Stage,
    val note: String,
    val candidates: Int = 0,
    val sideRatio: Double? = null,
    val wedge: DoubleArray? = null,
    val contrast: Double? = null,
    val bits: IntArray? = null,
    val docId: String? = null,
    val crcOk: Boolean = false,
    val spots: List<Int>? = null,
    val mid: Double? = null,
    val high: Double? = null,
    val copySuspect: Boolean = false,
) {
    val complete: Boolean get() = stage == Stage.SPOTS && docId != null
}

object Decoder {

    private const val MIN_CONTRAST = 40.0
    private const val OPPOSITE_SIDE_TOLERANCE = 0.35
    private const val MAX_CANDIDATES = 14

    /** @param gray single-channel Y plane, already rotated upright. */
    fun decode(gray: Mat): Frame {
        val quads = findFiducialCandidates(gray)
        if (quads.size < 4) {
            return Frame(Stage.SEARCHING, "Looking for the four corner squares", quads.size)
        }

        val corners = pickCornerQuad(quads)
            ?: return Frame(Stage.SEARCHING, "Corner squares do not form a code outline", quads.size)

        val warped = Mat()
        val src = MatOfPoint2f(*corners.ordered.map { Point(it[0], it[1]) }.toTypedArray())
        val dst = MatOfPoint2f(*Spec.FIDUCIALS.map { Point(it[0], it[1]) }.toTypedArray())
        val m = Imgproc.getPerspectiveTransform(src, dst)
        Imgproc.warpPerspective(gray, warped, m, Size(Spec.W, Spec.H))
        src.release(); dst.release(); m.release()

        val flat = flattenIllumination(warped)
        warped.release()

        // Absolute threshold from the wedge. A variable spot count means we
        // cannot infer the threshold from how many things are dark.
        val wedge = DoubleArray(4) { sample(flat, Spec.WEDGE[it][0], Spec.WEDGE[it][1], 5.0) }
        val contrast = wedge[3] - wedge[0]
        if (contrast < MIN_CONTRAST) {
            flat.release()
            return Frame(
                Stage.WEDGE,
                "Not enough contrast on the grey wedge. More light, or less glare.",
                quads.size, corners.ratio, wedge, contrast,
            )
        }
        val threshold = (wedge[0] + wedge[3]) / 2.0

        val bits = IntArray(Spec.RING_SLOTS) {
            val p = Spec.slot(it)
            if (sample(flat, p[0], p[1], Spec.BIT_SET_RADIUS * 0.7) < threshold) 1 else 0
        }

        if (!Spec.syncMatches(bits)) {
            flat.release()
            return Frame(
                Stage.RING, "Sync word not found. This is not a ThumbCode ring.",
                quads.size, corners.ratio, wedge, contrast, bits,
            )
        }
        val idb = Spec.idBytes(bits)
        val crcOk = Spec.crc16(idb) == Spec.crcFromBits(bits)
        if (!crcOk) {
            flat.release()
            return Frame(
                Stage.RING, "Ring checksum failed. Hold steadier.",
                quads.size, corners.ratio, wedge, contrast, bits,
            )
        }
        val docId = Spec.hex(idb)

        val spots = (0 until Spec.ANCHOR_COUNT).filter {
            val a = Spec.ANCHORS[it]
            sample(flat, a[0], a[1], Spec.SPOT_RADIUS * 0.55) < threshold
        }

        val span = wedge[3] - wedge[0]
        val mid = (wedge[1] - wedge[0]) / span
        val high = (wedge[2] - wedge[0]) / span
        // Copiers clip midtones toward the extremes. A screen has gamma near 2.2
        // and pushes both values down together, so only flag when the two are
        // pushed APART — a plain deviation test flags every screen scan.
        val copySuspect = mid < 0.22 && high > 0.82

        flat.release()
        return Frame(
            Stage.SPOTS, "Decoded", quads.size, corners.ratio, wedge, contrast,
            bits, docId, true, spots, mid, high, copySuspect,
        )
    }

    // ---- stages ----

    private fun findFiducialCandidates(gray: Mat): List<MatOfPoint> {
        val work = Mat()
        Imgproc.GaussianBlur(gray, work, Size(5.0, 5.0), 0.0)
        Imgproc.adaptiveThreshold(
            work, work, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 7.0,
        )
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(work, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        work.release()

        val minArea = gray.total() * 0.00008
        val keep = contours.filter { c ->
            val area = Imgproc.contourArea(c)
            if (area < minArea) return@filter false
            val c2f = MatOfPoint2f(*c.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.05 * Imgproc.arcLength(c2f, true), true)
            val pts = approx.toArray()
            c2f.release(); approx.release()
            if (pts.size != 4) return@filter false
            val poly = MatOfPoint(*pts)
            val convex = Imgproc.isContourConvex(poly)
            val box = Imgproc.boundingRect(poly)
            poly.release()
            if (!convex) return@filter false
            val aspect = box.width.toDouble() / box.height
            // Square-ish, and filling most of its bounding box: a solid square
            // does, a letter or a seal edge does not.
            aspect in 0.65..1.55 && area / (box.width.toDouble() * box.height) > 0.72
        }
        return keep.sortedByDescending { Imgproc.contourArea(it) }.take(MAX_CANDIDATES)
    }

    private class Corners(val ordered: List<DoubleArray>, val ratio: Double)

    /**
     * Search combinations of four candidates for the quad whose centres have a
     * side ratio closest to the canonical corner box, then order them.
     */
    private fun pickCornerQuad(quads: List<MatOfPoint>): Corners? {
        val centres = quads.map { c ->
            val mo = Imgproc.moments(c)
            doubleArrayOf(mo.m10 / mo.m00, mo.m01 / mo.m00)
        }
        val areas = quads.map { Imgproc.contourArea(it) }

        var best: Corners? = null
        var bestErr = Double.MAX_VALUE
        val n = centres.size

        for (a in 0 until n - 3) for (b in a + 1 until n - 2)
            for (c in b + 1 until n - 1) for (d in c + 1 until n) {
                val idx = intArrayOf(a, b, c, d)
                val pts = idx.map { centres[it] }
                val cx = pts.sumOf { it[0] } / 4
                val cy = pts.sumOf { it[1] } / 4

                // Sorting by angle about the centroid runs clockwise in image
                // coordinates, because y grows downward.
                val order = idx.sortedBy { atan2(centres[it][1] - cy, centres[it][0] - cx) }
                val p = order.map { centres[it] }

                val sides = DoubleArray(4) {
                    hypot(p[it][0] - p[(it + 1) % 4][0], p[it][1] - p[(it + 1) % 4][1])
                }
                if (sides.any { it < 1e-3 }) continue
                // Reject wild perspective: opposite sides must roughly match.
                if (abs(sides[0] - sides[2]) / max(sides[0], sides[2]) > OPPOSITE_SIDE_TOLERANCE) continue
                if (abs(sides[1] - sides[3]) / max(sides[1], sides[3]) > OPPOSITE_SIDE_TOLERANCE) continue

                val u = (sides[0] + sides[2]) / 2
                val v = (sides[1] + sides[3]) / 2
                val ratio = min(u, v) / max(u, v)
                val err = abs(ratio - Spec.CORNER_BOX_RATIO)
                if (err > 0.16 || err >= bestErr) continue

                // Rotate the clockwise ordering so the smallest square leads.
                // That is bottom-right by construction, so the rest fall out as
                // BL, TL, TR with no rotation search and no 180 degree ambiguity.
                val smallest = order.indices.minByOrNull { areas[order[it]] }!!
                val rotated = (0 until 4).map { p[(smallest + it) % 4] }

                bestErr = err
                best = Corners(rotated, ratio)
            }
        return best
    }

    /**
     * Divide by a heavily blurred copy of itself. This is what makes glare and
     * backlight gradients survivable — without it a lamp reflection on one half
     * of the sheet takes the whole ring with it.
     */
    private fun flattenIllumination(warped: Mat): Mat {
        val f = Mat()
        warped.convertTo(f, CvType.CV_32F)
        val bg = Mat()
        Imgproc.GaussianBlur(f, bg, Size(0.0, 0.0), 41.0)
        Core.add(bg, Scalar(1.0), bg)
        Core.divide(f, bg, f)
        Core.multiply(f, Scalar(160.0), f)
        val out = Mat()
        f.convertTo(out, CvType.CV_8U)
        f.release(); bg.release()
        return out
    }

    /** Mean intensity of a small disc, in canonical coordinates. */
    private fun sample(canvas: Mat, x: Double, y: Double, r: Double): Double {
        val x0 = max(0, (x - r).toInt())
        val y0 = max(0, (y - r).toInt())
        val x1 = min(canvas.cols() - 1, (x + r).toInt())
        val y1 = min(canvas.rows() - 1, (y + r).toInt())
        if (x1 <= x0 || y1 <= y0) return 255.0
        var sum = 0.0
        var count = 0
        val row = ByteArray(x1 - x0 + 1)
        for (yy in y0..y1) {
            canvas.get(yy, x0, row)
            for (i in row.indices) {
                sum += (row[i].toInt() and 0xFF)
                count++
            }
        }
        return if (count == 0) 255.0 else sum / count
    }
}
