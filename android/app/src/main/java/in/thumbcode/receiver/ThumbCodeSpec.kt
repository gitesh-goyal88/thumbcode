package `in`.thumbcode.receiver

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ThumbCode canonical spec — Android copy.
 *
 * This must agree byte for byte with supabase/functions/tc/spec.ts and the
 * <script> block in portal/index.html. Known-good values, checked against the
 * other two implementations:
 *
 *   crc16("123456789" as ASCII)              == 0x29B1
 *   ringBits("a3f9c1d0e7")                   == 1101001110100011111110011100000111010000111001110011000010101001
 *   ANCHORS.size                             == 32
 *   minimum anchor centre separation         == 34.05 units
 */
object Spec {
    const val W = 400.0
    const val H = 480.0
    const val CX = 200.0
    const val CY = 215.0
    const val RX = 155.0
    const val RY = 180.0

    const val RING_FACTOR = 0.90
    const val RING_SLOTS = 64
    val SYNC = intArrayOf(1, 1, 0, 1, 0, 0, 1, 1)
    const val ID_BYTES = 5

    const val SPOT_RADIUS = 11.0
    const val BIT_SET_RADIUS = 4.2

    /** Fiducial centres in canonical space, in decoder order: BR, BL, TL, TR. */
    val FIDUCIALS = arrayOf(
        doubleArrayOf(379.0, 459.0),
        doubleArrayOf(21.0, 459.0),
        doubleArrayOf(21.0, 21.0),
        doubleArrayOf(379.0, 21.0),
    )

    /** 358 x 438 corner box, so 0.8174. The quad search targets this. */
    const val CORNER_BOX_RATIO = 358.0 / 438.0

    /** Wedge patch centres, left to right: black, mid, high, white. */
    val WEDGE = arrayOf(
        doubleArrayOf(158.0, 432.0),
        doubleArrayOf(186.0, 432.0),
        doubleArrayOf(214.0, 432.0),
        doubleArrayOf(242.0, 432.0),
    )

    private data class Ring(val factor: Double, val count: Int, val phase: Double)

    private val ANCHOR_RINGS = listOf(
        Ring(0.28, 8, 0.0),
        Ring(0.52, 12, 0.5),
        Ring(0.74, 12, 0.0),
    )

    /** Ring by ring, in order. The index travels over the wire, so this ordering is protocol. */
    val ANCHORS: Array<DoubleArray> = buildList {
        for (r in ANCHOR_RINGS) {
            for (i in 0 until r.count) {
                val t = 2 * PI * (i + r.phase) / r.count - PI / 2
                add(doubleArrayOf(CX + RX * r.factor * cos(t), CY + RY * r.factor * sin(t)))
            }
        }
    }.toTypedArray()

    val ANCHOR_COUNT = ANCHORS.size

    /** Centre of ring slot j in canonical space. */
    fun slot(j: Int): DoubleArray {
        val t = 2 * PI * j / RING_SLOTS - PI / 2
        return doubleArrayOf(
            CX + RX * RING_FACTOR * cos(t),
            CY + RY * RING_FACTOR * sin(t),
        )
    }

    fun crc16(bytes: IntArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor ((b and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun syncMatches(bits: IntArray): Boolean =
        SYNC.indices.all { bits[it] == SYNC[it] }

    /** Bits 8..47, most significant bit first, as 5 bytes. */
    fun idBytes(bits: IntArray): IntArray {
        val out = IntArray(ID_BYTES)
        for (b in 0 until ID_BYTES) {
            var v = 0
            for (i in 0 until 8) v = (v shl 1) or bits[8 + b * 8 + i]
            out[b] = v
        }
        return out
    }

    fun crcFromBits(bits: IntArray): Int {
        var v = 0
        for (i in 48 until 64) v = (v shl 1) or bits[i]
        return v
    }

    fun hex(bytes: IntArray): String =
        bytes.joinToString("") { it.toString(16).padStart(2, '0') }
}
