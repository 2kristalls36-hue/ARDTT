package com.nonamevpn.app.bypass

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WRAP key + RTP-AEAD (qWDTT / SpaceNeuroX Path B; protocol salt still "WDTT-WRAP-v1").
 * Key: HKDF-SHA256(password, salt="WDTT-WRAP-v1", info="rtp-obfs/chacha20poly1305").
 * Packet: [RTP 12B AAD][ChaCha20-Poly1305 ciphertext+tag][padding][padLen].
 */
object WrapCrypto {
    const val KEY_LEN = 32
    private const val TAG_LEN = 16
    private const val RTP_HEADER = 12
    private const val SALT = "WDTT-WRAP-v1"
    private const val INFO = "rtp-obfs/chacha20poly1305"

    fun deriveKey(password: String): ByteArray {
        require(password.isNotEmpty()) { "empty password" }
        return hkdfSha256(
            ikm = password.toByteArray(Charsets.UTF_8),
            salt = SALT.toByteArray(Charsets.UTF_8),
            info = INFO.toByteArray(Charsets.UTF_8),
            length = KEY_LEN,
        )
    }

    fun wrap(
        key: ByteArray,
        payload: ByteArray,
        ssrc: Int,
        payloadType: Byte = 111,
        state: ObfsState,
        paddingMax: Int = 0,
    ): ByteArray {
        require(key.size == KEY_LEN)
        require(payload.isNotEmpty())
        val c = state.nextCount()
        val seq = ((state.initSeq + c) and 0xFFFF).toInt()
        val ts = state.initTs + c.toInt() * 960 + (c shr 16).toInt()
        val nonce = buildNonce(ssrc, seq, ts)

        val padRand = if (paddingMax > 0) SecureRandom().nextInt(paddingMax) else 0
        val padTotal = padRand + 1
        val out = ByteArray(RTP_HEADER + payload.size + TAG_LEN + padTotal)

        out[0] = (0x80 or 0x20).toByte() // V=2, P=1
        out[1] = (payloadType.toInt() and 0x7F).toByte()
        putU16(out, 2, seq)
        putU32(out, 4, ts)
        putU32(out, 8, ssrc)

        val aad = out.copyOfRange(0, RTP_HEADER)
        val sealed = chachaSeal(key, nonce, aad, payload)
        System.arraycopy(sealed, 0, out, RTP_HEADER, sealed.size)
        if (padRand > 0) {
            val padBytes = ByteArray(padRand)
            SecureRandom().nextBytes(padBytes)
            System.arraycopy(padBytes, 0, out, RTP_HEADER + sealed.size, padRand)
        }
        out[out.size - 1] = padTotal.toByte()
        return out
    }

    fun unwrap(key: ByteArray, wire: ByteArray): ByteArray {
        require(key.size == KEY_LEN)
        require(wire.size >= RTP_HEADER + 1) { "packet too short" }
        require((wire[0].toInt() ushr 6) and 0x3 == 2) { "not RTP v2" }

        var headerLen = RTP_HEADER
        if (wire[0].toInt() and 0x10 != 0) headerLen = 24
        require(wire.size >= headerLen + 1) { "packet too short for extension" }

        val seq = getU16(wire, 2)
        val ts = getU32(wire, 4)
        val ssrc = getU32(wire, 8)
        var payloadEnd = wire.size
        if (wire[0].toInt() and 0x20 != 0) {
            val padLen = wire[wire.size - 1].toInt() and 0xFF
            require(padLen in 1..(payloadEnd - headerLen)) { "invalid padding" }
            payloadEnd -= padLen
        }
        val cipherLen = payloadEnd - headerLen
        require(cipherLen > TAG_LEN) { "no payload" }
        val nonce = buildNonce(ssrc, seq, ts)
        val aad = wire.copyOfRange(0, headerLen)
        val ciphertext = wire.copyOfRange(headerLen, payloadEnd)
        return chachaOpen(key, nonce, aad, ciphertext)
    }

    private fun buildNonce(ssrc: Int, seq: Int, ts: Int): ByteArray {
        val n = ByteArray(12)
        putU32(n, 0, ssrc)
        putU16(n, 4, seq)
        // bytes 6-7 zero
        putU32(n, 8, ts)
        return n
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(counter.toByte()))
            t = mac.doFinal()
            val copy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copy)
            offset += copy
            counter++
        }
        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Android Cipher ChaCha20-Poly1305 (API 28+): nonce 12B, AAD = RTP header. */
    private fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun putU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun getU16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun getU32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    class ObfsState(
        val initSeq: Int = SecureRandom().nextInt(0x10000),
        val initTs: Int = SecureRandom().nextInt(),
    ) {
        @Volatile private var count: Long = 0
        @Synchronized fun nextCount(): Long = count++
    }
}
