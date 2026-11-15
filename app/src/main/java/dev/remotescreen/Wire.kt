package dev.remotescreen

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto and framing toolbox shared by the pairing server, the session server
 * and the receiver. Only standard JCA primitives: P-256 ECDH, HKDF-SHA256,
 * HMAC-SHA256 and AES-256-GCM.
 */
object Wire {

    const val PROTOCOL = 1

    val random: SecureRandom = SecureRandom()

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        return g.generateKeyPair()
    }

    fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun ecdh(privateKey: PrivateKey, peer: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(block)
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Six digit short authentication string; both ends compute the same value. */
    fun pairingCode(master: ByteArray, devicePub: ByteArray, receiverPub: ByteArray): String {
        val mac = hmac(master, "code".toByteArray(Charsets.UTF_8), devicePub, receiverPub)
        val value = (ByteBuffer.wrap(mac).int.toLong() and 0xFFFFFFFFL) % 1_000_000L
        return value.toString().padStart(6, '0')
    }

    fun fingerprint(publicKeyEncoded: ByteArray): ByteArray = sha256(publicKeyEncoded)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

data class Message(val type: Int, val payload: ByteArray)

/** Length framed, AES-256-GCM protected message channel (one per direction counter). */
class SecureChannel(
    private val din: DataInputStream,
    private val dout: DataOutputStream,
    key: ByteArray
) {

    private val keySpec = SecretKeySpec(key, "AES")
    private val sendLock = Any()
    private var sendSeq = 0L
    private var recvSeq = 0L

    fun send(type: Int, payload: ByteArray) {
        synchronized(sendLock) {
            val plain = ByteArray(payload.size + 1)
            plain[0] = type.toByte()
            System.arraycopy(payload, 0, plain, 1, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(sendSeq)))
            val sealed = cipher.doFinal(plain)
            sendSeq++
            dout.writeInt(sealed.size)
            dout.write(sealed)
            dout.flush()
        }
    }

    /** Blocks for the next message; null means the peer closed the stream. */
    fun receive(): Message? {
        val length = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        } catch (e: IOException) {
            return null
        }
        if (length < 17 || length > 8 * 1024 * 1024) throw IOException("bad frame length $length")
        val sealed = ByteArray(length)
        din.readFully(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(recvSeq)))
        val plain = cipher.doFinal(sealed)
        recvSeq++
        if (plain.isEmpty()) throw IOException("empty frame")
        return Message(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }

    private fun nonce(seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
}
