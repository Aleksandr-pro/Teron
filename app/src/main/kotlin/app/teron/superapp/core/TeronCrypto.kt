package app.teron.superapp.core

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TeronCrypto {
    private val rng = SecureRandom()

    private val seedWords = arrayOf(
        "океан", "маяк", "тайга", "кедр", "волна", "зенит", "пульс", "кварц",
        "север", "берег", "ветер", "пламя", "орбита", "сокол", "гранит", "иней",
        "луч", "шторм", "парус", "кристалл", "азимут", "комета", "рассвет", "скала",
        "титан", "сфера", "молния", "прибой", "звезда", "туман", "компас", "рубин"
    )

    data class GeneratedIdentity(
        val nodeId: String,
        val walletAddress: String,
        val publicKeyB64: String,
        val privateKeyB64: String,
        val seedPhrase: String
    )

    data class CipherPayload(
        val cipherTextB64: String,
        val ivB64: String
    )

    fun generateIdentity(): GeneratedIdentity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        val kp = kpg.generateKeyPair()
        val pubBytes = kp.public.encoded
        val privBytes = kp.private.encoded

        val digest = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        val nodeHex = digest.take(4).joinToString("") { "%02X".format(it) }
        val walletHex = digest.take(16).joinToString("") { "%02x".format(it) }

        val words = (1..12).map { seedWords[rng.nextInt(seedWords.size)] }.joinToString(" ")
        return GeneratedIdentity(
            nodeId = "TRN-$nodeHex",
            walletAddress = "trn1$walletHex",
            publicKeyB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP),
            privateKeyB64 = Base64.encodeToString(privBytes, Base64.NO_WRAP),
            seedPhrase = words
        )
    }

    fun fingerprintHex(publicKeyB64: String): String {
        return try {
            val raw = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            hash.take(12).chunked(2).joinToString(":") { pair ->
                pair.joinToString("") { "%02X".format(it) }
            }
        } catch (_: Exception) {
            "00:00:00:00:00:00"
        }
    }

    private fun decodePublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun decodePrivateKey(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    fun derivePeerKey(myPrivateKeyB64: String, peerPublicKeyB64: String): ByteArray {
        return try {
            val priv = decodePrivateKey(myPrivateKeyB64)
            val pub = decodePublicKey(peerPublicKeyB64)
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(priv)
            ka.doPhase(pub, true)
            val shared = ka.generateSecret()
            hkdfSha256(shared, "TERON-AIRMESH-E2EE-V1".toByteArray())
        } catch (_: Exception) {
            deriveChannelKey("fallback")
        }
    }

    fun deriveChannelKey(channelTag: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("TERON-CHANNEL-KEY-V1::$channelTag".toByteArray(Charsets.UTF_8))
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("TERON-SALT-2026".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(byteArrayOf(0x01))
        return mac.doFinal()
    }

    fun encryptAesGcm(plainText: String, key32: ByteArray): CipherPayload {
        val iv = ByteArray(12)
        rng.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32.copyOf(32), "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return CipherPayload(
            cipherTextB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptAesGcm(cipherTextB64: String, ivB64: String, key32: ByteArray): String? {
        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val enc = Base64.decode(cipherTextB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32.copyOf(32), "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun signData(privateKeyB64: String, payload: String): String {
        return try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(decodePrivateKey(privateKeyB64))
            sig.update(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun verifySignature(publicKeyB64: String, payload: String, signatureB64: String): Boolean {
        if (publicKeyB64.isBlank() || signatureB64.isBlank()) return false
        return try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(decodePublicKey(publicKeyB64))
            sig.update(payload.toByteArray(Charsets.UTF_8))
            sig.verify(Base64.decode(signatureB64, Base64.NO_WRAP))
        } catch (_: Exception) {
            false
        }
    }

    fun shortSha256Hex(input: String, bytes: Int = 8): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(bytes).joinToString("") { "%02x".format(it) }
    }
}
