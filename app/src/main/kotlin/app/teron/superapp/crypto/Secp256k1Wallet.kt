package app.teron.superapp.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Production non-custodial multi-chain HD wallet engine implementing:
 * - BIP-39 (128/256-bit entropy <-> 12/24 English mnemonic words + PBKDF2-HMAC-SHA512)
 * - BIP-32 / BIP-44 / BIP-84 hierarchical deterministic key derivation on secp256k1
 * - Bitcoin Native SegWit (m/84'/0'/0'/0/0 -> bc1q... Bech32 P2WPKH)
 * - Ethereum / EVM / USDT ERC-20 (m/44'/60'/0'/0/0 -> 0x... EIP-55 checksummed)
 * - TRON / USDT TRC-20 (m/44'/195'/0'/0/0 -> T... Base58Check)
 * - Deterministic RFC-6979 secp256k1 ECDSA signing + EIP-155 RLP raw transaction encoding
 */
object Secp256k1Wallet {

    private val rng = SecureRandom()

    // secp256k1 domain parameters
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val HALF_N = N.shiftRight(1)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2L)
    private val THREE = BigInteger.valueOf(3L)

    data class EcPoint(val x: BigInteger, val y: BigInteger, val infinity: Boolean = false) {
        companion object {
            val INFINITY = EcPoint(BigInteger.ZERO, BigInteger.ZERO, true)
        }
    }

    private val G = EcPoint(GX, GY, false)

    data class DerivedMultiChainWallet(
        val mnemonic: String,
        val btcAddress: String,
        val btcPrivKeyHex: String,
        val ethAddress: String,
        val ethPrivKeyHex: String,
        val trxAddress: String,
        val trxPrivKeyHex: String
    )

    data class EcdsaSig(
        val r: BigInteger,
        val s: BigInteger,
        val recoveryId: Int
    )

    // =========================================================================
    // 1. BIP-39 MNEMONIC GENERATION, VALIDATION & SEED DERIVATION
    // =========================================================================
    fun generateMnemonic12(): String {
        val entropy = ByteArray(16)
        rng.nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size in intArrayOf(16, 20, 24, 28, 32))
        val hash = sha256(entropy)
        val entBits = entropy.size * 8
        val csBits = entBits / 32
        val totalBits = entBits + csBits

        val bits = BooleanArray(totalBits)
        for (i in 0 until entBits) {
            bits[i] = ((entropy[i / 8].toInt() ushr (7 - (i % 8))) and 1) == 1
        }
        for (i in 0 until csBits) {
            bits[entBits + i] = ((hash[i / 8].toInt() ushr (7 - (i % 8))) and 1) == 1
        }

        val words = ArrayList<String>(totalBits / 11)
        for (w in 0 until totalBits / 11) {
            var idx = 0
            for (b in 0 until 11) {
                idx = (idx shl 1) or (if (bits[w * 11 + b]) 1 else 0)
            }
            words.add(Bip39Wordlist.words[idx])
        }
        return words.joinToString(" ")
    }

    fun normalizeMnemonic(raw: String): String =
        Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFKD)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    fun validateMnemonic(raw: String): Boolean {
        val words = normalizeMnemonic(raw).split(' ').filter { it.isNotEmpty() }
        if (words.size !in intArrayOf(12, 15, 18, 21, 24)) return false

        val totalBits = words.size * 11
        val bits = BooleanArray(totalBits)
        for ((wIdx, word) in words.withIndex()) {
            val code = Bip39Wordlist.wordIndex[word] ?: return false
            for (b in 0 until 11) {
                bits[wIdx * 11 + b] = ((code ushr (10 - b)) and 1) == 1
            }
        }

        val csBits = totalBits / 33
        val entBits = totalBits - csBits
        val entropy = ByteArray(entBits / 8)
        for (i in 0 until entBits) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        val hash = sha256(entropy)
        for (i in 0 until csBits) {
            val expected = ((hash[i / 8].toInt() ushr (7 - (i % 8))) and 1) == 1
            if (bits[entBits + i] != expected) return false
        }
        return true
    }

    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normMnemonic = normalizeMnemonic(mnemonic).toByteArray(Charsets.UTF_8)
        val normSalt = ("mnemonic" + Normalizer.normalize(passphrase, Normalizer.Form.NFKD)).toByteArray(Charsets.UTF_8)
        return pbkdf2HmacSha512(normMnemonic, normSalt, 2048, 64)
    }

    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = 64
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)

        for (block in 1..blocks) {
            val blockSalt = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, blockSalt, 0, salt.size)
            blockSalt[salt.size] = ((block ushr 24) and 0xFF).toByte()
            blockSalt[salt.size + 1] = ((block ushr 16) and 0xFF).toByte()
            blockSalt[salt.size + 2] = ((block ushr 8) and 0xFF).toByte()
            blockSalt[salt.size + 3] = (block and 0xFF).toByte()

            var u = mac.doFinal(blockSalt)
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in 0 until hLen) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    // =========================================================================
    // 2. BIP-32 HD KEY DERIVATION & MULTI-CHAIN ADDRESSES
    // =========================================================================
    private data class ExtKey(val priv: BigInteger, val chainCode: ByteArray)

    private fun masterKeyFromSeed(seed: ByteArray): ExtKey {
        val i = hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val ir = i.copyOfRange(32, 64)
        return ExtKey(il, ir)
    }

    private fun deriveChild(parent: ExtKey, index: Int): ExtKey {
        val hardened = (index and -0x80000000) != 0
        val data = if (hardened) {
            ByteArray(37).also { buf ->
                buf[0] = 0
                val pBytes = bigIntTo32Bytes(parent.priv)
                System.arraycopy(pBytes, 0, buf, 1, 32)
                writeIntBE(buf, 33, index)
            }
        } else {
            ByteArray(37).also { buf ->
                val pub33 = publicKeyCompressed(parent.priv)
                System.arraycopy(pub33, 0, buf, 0, 33)
                writeIntBE(buf, 33, index)
            }
        }
        val i = hmacSha512(parent.chainCode, data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val childPriv = il.add(parent.priv).mod(N)
        return ExtKey(childPriv, i.copyOfRange(32, 64))
    }

    private fun derivePath(seed: ByteArray, pathIndices: IntArray): BigInteger {
        var cur = masterKeyFromSeed(seed)
        for (idx in pathIndices) {
            cur = deriveChild(cur, idx)
        }
        return cur.priv
    }

    private fun h(i: Int): Int = i or -0x80000000

    fun deriveMultiChain(mnemonicRaw: String): DerivedMultiChainWallet {
        val clean = normalizeMnemonic(mnemonicRaw)
        val seed = mnemonicToSeed(clean)

        // BTC Native SegWit: m/84'/0'/0'/0/0
        val btcPriv = derivePath(seed, intArrayOf(h(84), h(0), h(0), 0, 0))
        val btcAddr = pubKeyToBtcBech32(publicKeyCompressed(btcPriv))

        // ETH / EVM: m/44'/60'/0'/0/0
        val ethPriv = derivePath(seed, intArrayOf(h(44), h(60), h(0), 0, 0))
        val ethAddr = pubKeyToEthEip55(publicKeyUncompressed64(ethPriv))

        // TRON: m/44'/195'/0'/0/0
        val trxPriv = derivePath(seed, intArrayOf(h(44), h(195), h(0), 0, 0))
        val trxAddr = pubKeyToTronBase58(publicKeyUncompressed64(trxPriv))

        return DerivedMultiChainWallet(
            mnemonic = clean,
            btcAddress = btcAddr,
            btcPrivKeyHex = toHex(bigIntTo32Bytes(btcPriv)),
            ethAddress = ethAddr,
            ethPrivKeyHex = toHex(bigIntTo32Bytes(ethPriv)),
            trxAddress = trxAddr,
            trxPrivKeyHex = toHex(bigIntTo32Bytes(trxPriv))
        )
    }

    // =========================================================================
    // 3. SECP256K1 ELLIPTIC CURVE MATH & RFC-6979 ECDSA SIGNING
    // =========================================================================
    private fun pointDouble(p: EcPoint): EcPoint {
        if (p.infinity || p.y.signum() == 0) return EcPoint.INFINITY
        val num = p.x.multiply(p.x).multiply(THREE).mod(P)
        val den = p.y.multiply(TWO).modInverse(P)
        val lam = num.multiply(den).mod(P)
        val rx = lam.multiply(lam).subtract(p.x.multiply(TWO)).mod(P)
        val ry = lam.multiply(p.x.subtract(rx)).subtract(p.y).mod(P)
        return EcPoint(rx, ry)
    }

    private fun pointAdd(p: EcPoint, q: EcPoint): EcPoint {
        if (p.infinity) return q
        if (q.infinity) return p
        if (p.x == q.x) {
            return if (p.y == q.y) pointDouble(p) else EcPoint.INFINITY
        }
        val num = q.y.subtract(p.y).mod(P)
        val den = q.x.subtract(p.x).mod(P).modInverse(P)
        val lam = num.multiply(den).mod(P)
        val rx = lam.multiply(lam).subtract(p.x).subtract(q.x).mod(P)
        val ry = lam.multiply(p.x.subtract(rx)).subtract(p.y).mod(P)
        return EcPoint(rx, ry)
    }

    fun scalarMultiply(k: BigInteger, point: EcPoint = G): EcPoint {
        var scalar = k.mod(N)
        if (scalar.signum() == 0) return EcPoint.INFINITY
        var result = EcPoint.INFINITY
        var addend = point
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) {
                result = pointAdd(result, addend)
            }
            addend = pointDouble(addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    fun publicKeyCompressed(priv: BigInteger): ByteArray {
        val pt = scalarMultiply(priv)
        val out = ByteArray(33)
        out[0] = if (pt.y.testBit(0)) 0x03 else 0x02
        System.arraycopy(bigIntTo32Bytes(pt.x), 0, out, 1, 32)
        return out
    }

    fun publicKeyUncompressed64(priv: BigInteger): ByteArray {
        val pt = scalarMultiply(priv)
        val out = ByteArray(64)
        System.arraycopy(bigIntTo32Bytes(pt.x), 0, out, 0, 32)
        System.arraycopy(bigIntTo32Bytes(pt.y), 0, out, 32, 32)
        return out
    }

    fun signHash32(privKeyHex: String, hash32: ByteArray): EcdsaSig {
        require(hash32.size == 32)
        val priv = BigInteger(privKeyHex.removePrefix("0x"), 16)
        val z = BigInteger(1, hash32)
        val xBytes = bigIntTo32Bytes(priv)

        // Deterministic RFC-6979 HMAC-DRBG (SHA-256)
        var v = ByteArray(32) { 0x01 }
        var kMac = ByteArray(32) { 0x00 }
        kMac = hmacSha256(kMac, v + byteArrayOf(0x00) + xBytes + hash32)
        v = hmacSha256(kMac, v)
        kMac = hmacSha256(kMac, v + byteArrayOf(0x01) + xBytes + hash32)
        v = hmacSha256(kMac, v)

        while (true) {
            v = hmacSha256(kMac, v)
            val kCandidate = BigInteger(1, v)
            if (kCandidate >= BigInteger.ONE && kCandidate < N) {
                val rPt = scalarMultiply(kCandidate)
                val r = rPt.x.mod(N)
                if (r.signum() != 0) {
                    var recId = (if (rPt.y.testBit(0)) 1 else 0) or (if (rPt.x >= N) 2 else 0)
                    var s = kCandidate.modInverse(N).multiply(z.add(r.multiply(priv))).mod(N)
                    if (s.signum() != 0) {
                        if (s > HALF_N) {
                            s = N.subtract(s)
                            recId = recId xor 1
                        }
                        return EcdsaSig(r, s, recId)
                    }
                }
            }
            kMac = hmacSha256(kMac, v + byteArrayOf(0x00))
            v = hmacSha256(kMac, v)
        }
    }

    // =========================================================================
    // 4. ETHEREUM EIP-55, RLP & EIP-155 RAW TRANSACTION SIGNING (ETH & ERC-20)
    // =========================================================================
    fun pubKeyToEthEip55(uncompressed64: ByteArray): String {
        val hash = keccak256(uncompressed64)
        val rawAddrHex = toHex(hash.copyOfRange(12, 32))
        return toEip55ChecksumAddress(rawAddrHex)
    }

    fun toEip55ChecksumAddress(addrHexNoPrefix: String): String {
        val lower = addrHexNoPrefix.removePrefix("0x").removePrefix("0X").lowercase()
        val hashHex = toHex(keccak256(lower.toByteArray(Charsets.US_ASCII)))
        val sb = StringBuilder(42)
        sb.append("0x")
        for (i in lower.indices) {
            val ch = lower[i]
            if (ch in 'a'..'f' && hashHex[i].digitToInt(16) >= 8) {
                sb.append(ch.uppercaseChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun isValidEthAddress(addr: String): Boolean {
        val clean = addr.trim()
        return clean.matches(Regex("^0x[0-9a-fA-F]{40}$"))
    }

    fun isValidBtcAddress(addr: String): Boolean {
        val clean = addr.trim()
        return clean.matches(Regex("^(bc1[ac-hj-np-z02-9]{25,87}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$"))
    }

    fun isValidTronAddress(addr: String): Boolean {
        val clean = addr.trim()
        return clean.matches(Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$"))
    }

    fun encodeErc20TransferData(toAddress: String, amountUnits: BigInteger): ByteArray {
        val cleanAddr = fromHex(toAddress.removePrefix("0x").removePrefix("0X"))
        require(cleanAddr.size == 20)
        val out = ByteArray(68)
        // selector a9059cbb = transfer(address,uint256)
        out[0] = 0xa9.toByte()
        out[1] = 0x05.toByte()
        out[2] = 0x9c.toByte()
        out[3] = 0xbb.toByte()
        System.arraycopy(cleanAddr, 0, out, 4 + 12, 20)
        val amtBytes = bigIntTo32Bytes(amountUnits)
        System.arraycopy(amtBytes, 0, out, 36, 32)
        return out
    }

    fun signEvmLegacyTransaction(
        privKeyHex: String,
        nonce: BigInteger,
        gasPriceWei: BigInteger,
        gasLimit: BigInteger,
        toAddressHex: String,
        valueWei: BigInteger,
        data: ByteArray = ByteArray(0),
        chainId: Int = 1
    ): String {
        val toBytes = fromHex(toAddressHex.removePrefix("0x").removePrefix("0X"))
        val unsignedItems = listOf(
            rlpEncodeBigInt(nonce),
            rlpEncodeBigInt(gasPriceWei),
            rlpEncodeBigInt(gasLimit),
            rlpEncodeBytes(toBytes),
            rlpEncodeBigInt(valueWei),
            rlpEncodeBytes(data),
            rlpEncodeBigInt(BigInteger.valueOf(chainId.toLong())),
            rlpEncodeBytes(ByteArray(0)),
            rlpEncodeBytes(ByteArray(0))
        )
        val unsignedRlp = rlpEncodeList(unsignedItems)
        val txHash = keccak256(unsignedRlp)
        val sig = signHash32(privKeyHex, txHash)
        val v = BigInteger.valueOf((chainId * 2 + 35 + sig.recoveryId).toLong())

        val signedItems = listOf(
            rlpEncodeBigInt(nonce),
            rlpEncodeBigInt(gasPriceWei),
            rlpEncodeBigInt(gasLimit),
            rlpEncodeBytes(toBytes),
            rlpEncodeBigInt(valueWei),
            rlpEncodeBytes(data),
            rlpEncodeBigInt(v),
            rlpEncodeBigInt(sig.r),
            rlpEncodeBigInt(sig.s)
        )
        return "0x" + toHex(rlpEncodeList(signedItems))
    }

    private fun rlpEncodeBigInt(v: BigInteger): ByteArray {
        if (v.signum() == 0) return rlpEncodeBytes(ByteArray(0))
        val raw = v.toByteArray()
        val stripped = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        return rlpEncodeBytes(stripped)
    }

    private fun rlpEncodeBytes(bytes: ByteArray): ByteArray {
        return when {
            bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80 -> bytes
            bytes.size <= 55 -> ByteArray(1 + bytes.size).also {
                it[0] = (0x80 + bytes.size).toByte()
                System.arraycopy(bytes, 0, it, 1, bytes.size)
            }
            else -> {
                val lenBytes = minimalBigEndian(bytes.size)
                ByteArray(1 + lenBytes.size + bytes.size).also {
                    it[0] = (0xB7 + lenBytes.size).toByte()
                    System.arraycopy(lenBytes, 0, it, 1, lenBytes.size)
                    System.arraycopy(bytes, 0, it, 1 + lenBytes.size, bytes.size)
                }
            }
        }
    }

    private fun rlpEncodeList(elements: List<ByteArray>): ByteArray {
        val total = elements.sumOf { it.size }
        val payload = ByteArray(total)
        var pos = 0
        for (el in elements) {
            System.arraycopy(el, 0, payload, pos, el.size)
            pos += el.size
        }
        return if (total <= 55) {
            ByteArray(1 + total).also {
                it[0] = (0xC0 + total).toByte()
                System.arraycopy(payload, 0, it, 1, total)
            }
        } else {
            val lenBytes = minimalBigEndian(total)
            ByteArray(1 + lenBytes.size + total).also {
                it[0] = (0xF7 + lenBytes.size).toByte()
                System.arraycopy(lenBytes, 0, it, 1, lenBytes.size)
                System.arraycopy(payload, 0, it, 1 + lenBytes.size, total)
            }
        }
    }

    private fun minimalBigEndian(n: Int): ByteArray {
        var v = n
        val tmp = ArrayList<Byte>(4)
        while (v > 0) {
            tmp.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        }
        return tmp.toByteArray()
    }

    // =========================================================================
    // 5. TRON BASE58CHECK & BITCOIN BECH32 (NATIVE SEGWIT bc1q...)
    // =========================================================================
    fun pubKeyToTronBase58(uncompressed64: ByteArray): String {
        val hash = keccak256(uncompressed64)
        val payload = ByteArray(21)
        payload[0] = 0x41.toByte()
        System.arraycopy(hash, 12, payload, 1, 20)
        return base58CheckEncode(payload)
    }

    fun pubKeyToBtcBech32(compressed33: ByteArray): String {
        val hash160 = ripemd160(sha256(compressed33))
        val words5 = convertBits(hash160, 8, 5, true)
        val data = IntArray(1 + words5.size)
        data[0] = 0 // witness version 0
        System.arraycopy(words5, 0, data, 1, words5.size)
        return bech32Encode("bc", data)
    }

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun base58CheckEncode(payload: ByteArray): String {
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        val full = payload + checksum
        var num = BigInteger(1, full)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58L)
        while (num > BigInteger.ZERO) {
            val qr = num.divideAndRemainder(base)
            sb.append(BASE58_ALPHABET[qr[1].toInt()])
            num = qr[0]
        }
        for (b in full) {
            if (b == 0.toByte()) sb.append('1') else break
        }
        return sb.reverse().toString()
    }

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun bech32Polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if (((top ushr i) and 1) != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun bech32Encode(hrp: String, data: IntArray): String {
        val hrpExp = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            hrpExp[i] = hrp[i].code ushr 5
            hrpExp[i + hrp.length + 1] = hrp[i].code and 31
        }
        hrpExp[hrp.length] = 0
        val values = IntArray(hrpExp.size + data.size + 6)
        System.arraycopy(hrpExp, 0, values, 0, hrpExp.size)
        System.arraycopy(data, 0, values, hrpExp.size, data.size)
        val pm = bech32Polymod(values) xor 1
        val sb = StringBuilder(hrp.length + 1 + data.size + 6)
        sb.append(hrp).append('1')
        for (d in data) sb.append(BECH32_CHARSET[d])
        for (i in 0..5) {
            sb.append(BECH32_CHARSET[(pm ushr (5 * (5 - i))) and 31])
        }
        return sb.toString()
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            val v = b.toInt() and 0xFF
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad && bits > 0) {
            out.add((acc shl (toBits - bits)) and maxv)
        }
        return out.toIntArray()
    }

    // =========================================================================
    // 6. KECCAK-256 (ORIGINAL ETHEREUM) & RIPEMD-160 (BITCOIN HASH160)
    // =========================================================================
    private val KECCAK_RC = longArrayOf(
        0x0000000000000001UL.toLong(), 0x0000000000008082UL.toLong(), 0x800000000000808aUL.toLong(), 0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(), 0x0000000080000001UL.toLong(), 0x8000000080008081UL.toLong(), 0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(), 0x0000000000000088UL.toLong(), 0x0000000080008009UL.toLong(), 0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(), 0x800000000000008bUL.toLong(), 0x8000000000008089UL.toLong(), 0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(), 0x8000000000000080UL.toLong(), 0x000000000000800aUL.toLong(), 0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(), 0x8000000000008080UL.toLong(), 0x0000000080000001UL.toLong(), 0x8000000080008008UL.toLong()
    )

    private val KECCAK_ROT = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    )

    fun keccak256(input: ByteArray): ByteArray {
        val rate = 136
        val state = LongArray(25)
        val paddedLen = ((input.size + rate) / rate) * rate
        val padded = ByteArray(paddedLen)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[paddedLen - 1] = (padded[paddedLen - 1].toInt() xor 0x80).toByte()

        var offset = 0
        while (offset < paddedLen) {
            for (i in 0 until rate / 8) {
                var lane = 0L
                for (b in 0..7) {
                    lane = lane or ((padded[offset + i * 8 + b].toLong() and 0xFFL) shl (8 * b))
                }
                state[i] = state[i] xor lane
            }
            keccakF1600(state)
            offset += rate
        }

        val out = ByteArray(32)
        for (i in 0..3) {
            val lane = state[i]
            for (b in 0..7) {
                out[i * 8 + b] = ((lane ushr (8 * b)) and 0xFFL).toByte()
            }
        }
        return out
    }

    private fun keccakF1600(s: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0..4) {
                c[x] = s[x] xor s[x + 5] xor s[x + 10] xor s[x + 15] xor s[x + 20]
            }
            for (x in 0..4) {
                d[x] = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0..20 step 5) {
                    s[x + y] = s[x + y] xor d[x]
                }
            }
            for (x in 0..4) {
                for (y in 0..4) {
                    val idx = x + 5 * y
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(s[idx], KECCAK_ROT[idx])
                }
            }
            for (x in 0..4) {
                for (y in 0..20 step 5) {
                    s[x + y] = b[x + y] xor (b[(x + 1) % 5 + y].inv() and b[(x + 2) % 5 + y])
                }
            }
            s[0] = s[0] xor KECCAK_RC[round]
        }
    }

    fun ripemd160(msg: ByteArray): ByteArray {
        val rl = intArrayOf(
            0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
            7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
            3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
            1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,
            4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13
        )
        val rr = intArrayOf(
            5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
            6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
            15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
            8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,
            12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11
        )
        val sl = intArrayOf(
            11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
            7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
            11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
            11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,
            9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6
        )
        val sr = intArrayOf(
            8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
            9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
            9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
            15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,
            8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11
        )
        val kl = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
        val kr = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000)

        fun f(j: Int, x: Int, y: Int, z: Int): Int = when (j / 16) {
            0 -> x xor y xor z
            1 -> (x and y) or (x.inv() and z)
            2 -> (x or y.inv()) xor z
            3 -> (x and z) or (y and z.inv())
            else -> x xor (y or z.inv())
        }

        val bitLen = msg.size.toLong() * 8L
        val padLen = ((56 - (msg.size + 1) % 64) + 64) % 64
        val buf = ByteArray(msg.size + 1 + padLen + 8)
        System.arraycopy(msg, 0, buf, 0, msg.size)
        buf[msg.size] = 0x80.toByte()
        for (i in 0..7) {
            buf[buf.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xFFL).toByte()
        }

        var h0 = 0x67452301
        var h1 = -0x10325477
        var h2 = -0x67452302
        var h3 = 0x10325476
        var h4 = -0x3c2d1e10

        val x = IntArray(16)
        var off = 0
        while (off < buf.size) {
            for (i in 0..15) {
                val p = off + i * 4
                x[i] = (buf[p].toInt() and 0xFF) or
                    ((buf[p + 1].toInt() and 0xFF) shl 8) or
                    ((buf[p + 2].toInt() and 0xFF) shl 16) or
                    ((buf[p + 3].toInt() and 0xFF) shl 24)
            }
            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4

            for (j in 0 until 80) {
                val tl = Integer.rotateLeft(al + f(j, bl, cl, dl) + x[rl[j]] + kl[j / 16], sl[j]) + el
                al = el; el = dl; dl = Integer.rotateLeft(cl, 10); cl = bl; bl = tl

                val tr = Integer.rotateLeft(ar + f(79 - j, br, cr, dr) + x[rr[j]] + kr[j / 16], sr[j]) + er
                ar = er; er = dr; dr = Integer.rotateLeft(cr, 10); cr = br; br = tr
            }
            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
            off += 64
        }

        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in 0..4) {
            out[i * 4] = (hs[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((hs[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((hs[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((hs[i] ushr 24) and 0xFF).toByte()
        }
        return out
    }

    // =========================================================================
    // 7. BYTE / HASH HELPERS
    // =========================================================================
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun bigIntTo32Bytes(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        val out = ByteArray(32)
        if (raw.size == 33 && raw[0] == 0.toByte()) {
            System.arraycopy(raw, 1, out, 0, 32)
        } else if (raw.size <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        } else {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        }
        return out
    }

    private fun writeIntBE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = ((v ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }

    fun toHex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(chars[v ushr 4]).append(chars[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
