package app.teron.superapp.crypto

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code matrix generator (Versions 1..8, Byte Mode, ECC Level L).
 * Renders scannable QR codes for Bitcoin (bc1q...), Ethereum (0x...), TRON (T...), and teron:// URIs.
 */
object QrCodeGenerator {

    // Total data codewords & ECC codewords per single block for ECC Level L, Versions 1..8
    private data class VersionSpec(val version: Int, val dataCodewords: Int, val ecCodewordsPerBlock: Int, val numBlocks: Int, val alignCenters: IntArray)

    private val SPECS = arrayOf(
        VersionSpec(1, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 136, 18, 2, intArrayOf(6, 34)),
        VersionSpec(7, 156, 20, 2, intArrayOf(6, 22, 38)),
        VersionSpec(8, 194, 24, 2, intArrayOf(6, 24, 42))
    )

    fun encode(text: String): Array<BooleanArray> {
        val payload = text.toByteArray(Charsets.UTF_8)
        // Byte mode header = 4 bits (0100) + 8 bits length (for v1..9) = 12 bits -> max payload = dataCodewords - 2
        val spec = SPECS.firstOrNull { payload.size + 2 <= it.dataCodewords } ?: SPECS.last()
        val usablePayload = if (payload.size + 2 > spec.dataCodewords) payload.copyOf(spec.dataCodewords - 2) else payload

        // 1. Bitstream construction (Byte Mode = 0b0100)
        val bits = ArrayList<Int>(spec.dataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        pushBits(0b0100, 4)
        pushBits(usablePayload.size, 8)
        for (b in usablePayload) pushBits(b.toInt() and 0xFF, 8)

        val capacityBits = spec.dataCodewords * 8
        val terminator = minOf(4, capacityBits - bits.size)
        repeat(terminator) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)

        val dataBytes = ByteArray(spec.dataCodewords)
        val encodedByteCount = bits.size / 8
        for (i in 0 until encodedByteCount) {
            var v = 0
            for (b in 0..7) v = (v shl 1) or bits[i * 8 + b]
            dataBytes[i] = v.toByte()
        }
        val padBytes = intArrayOf(0xEC, 0x11)
        for (i in encodedByteCount until spec.dataCodewords) {
            dataBytes[i] = padBytes[(i - encodedByteCount) % 2].toByte()
        }

        // 2. Split into blocks & compute Reed-Solomon ECC codewords
        val blockSize = spec.dataCodewords / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { b ->
            dataBytes.copyOfRange(b * blockSize, (b + 1) * blockSize)
        }
        val ecBlocks = Array(spec.numBlocks) { b ->
            reedSolomonRemainder(dataBlocks[b], spec.ecCodewordsPerBlock)
        }

        // Interleave data blocks + EC blocks
        val finalCodewords = ArrayList<Int>(spec.dataCodewords + spec.numBlocks * spec.ecCodewordsPerBlock)
        for (i in 0 until blockSize) {
            for (b in 0 until spec.numBlocks) finalCodewords.add(dataBlocks[b][i].toInt() and 0xFF)
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) finalCodewords.add(ecBlocks[b][i].toInt() and 0xFF)
        }

        // 3. Build matrix & function patterns
        val size = spec.version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunctionModule(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y][x] = dark
                isFunction[y][x] = true
            }
        }

        // Finder patterns + separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val xx = cx + dx
                    val yy = cy + dy
                    if (xx in 0 until size && yy in 0 until size) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunctionModule(xx, yy, dist != 2 && dist != 4)
                    }
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Timing patterns
        for (i in 0 until size) {
            if (!isFunction[6][i]) setFunctionModule(i, 6, i % 2 == 0)
            if (!isFunction[i][6]) setFunctionModule(6, i, i % 2 == 0)
        }

        // Alignment patterns
        val ac = spec.alignCenters
        for (cy in ac) {
            for (cx in ac) {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunctionModule(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format information areas + dark module
        for (i in 0..8) {
            if (i != 6) {
                isFunction[8][i] = true
                isFunction[i][8] = true
            }
        }
        for (i in 0..7) {
            isFunction[8][size - 1 - i] = true
            isFunction[size - 1 - i][8] = true
        }
        setFunctionModule(8, size - 8, true)

        if (spec.version >= 7) {
            // Version info bits for v7..v8
            var rem = spec.version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val vBits = (spec.version shl 12) or rem
            for (i in 0 until 18) {
                val bit = ((vBits ushr i) and 1) != 0
                val a = size - 11 + (i % 3)
                val b = i / 3
                setFunctionModule(a, b, bit)
                setFunctionModule(b, a, bit)
            }
        }

        // 4. Zigzag data placement
        var bitIdx = 0
        val totalDataBits = finalCodewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                val y = if (((right + 1) and 2) == 0) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y][x]) {
                        val dark = if (bitIdx < totalDataBits) {
                            ((finalCodewords[bitIdx / 8] ushr (7 - (bitIdx % 8))) and 1) != 0
                        } else false
                        modules[y][x] = dark
                        bitIdx++
                    }
                }
            }
            right -= 2
        }

        // 5. Choose best mask pattern (0..7) by penalty score
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0..7) {
            val candidate = Array(size) { r -> modules[r].clone() }
            applyMaskAndFormat(candidate, isFunction, mask)
            val pen = penaltyScore(candidate)
            if (pen < bestScore) {
                bestScore = pen
                bestMask = mask
            }
        }
        applyMaskAndFormat(modules, isFunction, bestMask)
        return modules
    }

    private fun applyMaskAndFormat(m: Array<BooleanArray>, isFunc: Array<BooleanArray>, mask: Int) {
        val size = m.size
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunc[y][x]) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (y / 2 + x / 3) % 2 == 0
                        5 -> (x * y) % 2 + (x * y) % 3 == 0
                        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                    }
                    if (invert) m[y][x] = !m[y][x]
                }
            }
        }

        // ECC Level L = 01
        val data = (0b01 shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

        // Top-left format info: MSB (bit 14) -> LSB (bit 0), skipping timing line 6
        val tlCoords = arrayOf(
            8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
            7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8
        )
        for (idx in 0..14) {
            val (r, c) = tlCoords[idx]
            m[r][c] = bit(14 - idx)
        }

        // Bottom-left vertical (col=8): bit 14 at bottommost row (size-1) up to bit 8 at (size-7)
        for (i in 0..6) {
            m[size - 1 - i][8] = bit(14 - i)
        }
        // Dark module at (row = size - 8, col = 8)
        m[size - 8][8] = true

        // Top-right horizontal (row=8): bit 7 at (size-8) rightwards to bit 0 at (size-1)
        for (i in 0..7) {
            m[8][size - 8 + i] = bit(7 - i)
        }
    }

    private fun penaltyScore(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0
        for (y in 0 until n) {
            var runLen = 1
            for (x in 1 until n) {
                if (m[y][x] == m[y][x - 1]) {
                    runLen++
                    if (runLen == 5) score += 3 else if (runLen > 5) score += 1
                } else runLen = 1
            }
        }
        for (x in 0 until n) {
            var runLen = 1
            for (y in 1 until n) {
                if (m[y][x] == m[y - 1][x]) {
                    runLen++
                    if (runLen == 5) score += 3 else if (runLen > 5) score += 1
                } else runLen = 1
            }
        }
        return score
    }

    // GF(256) with primitive polynomial 0x11D
    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun reedSolomonRemainder(data: ByteArray, degree: Int): ByteArray {
        val gen = ByteArray(degree)
        gen[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                gen[j] = (gfMul(gen[j].toInt() and 0xFF, root) xor (if (j + 1 < degree) (gen[j + 1].toInt() and 0xFF) else 0)).toByte()
            }
            root = gfMul(root, 0x02)
        }

        val result = ByteArray(degree)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, degree - 1)
            result[degree - 1] = 0
            for (j in 0 until degree) {
                result[j] = (result[j].toInt() xor gfMul(gen[j].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }
}
