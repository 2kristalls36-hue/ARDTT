package com.nonamevpn.app.unlock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline device unlock for alpha APKs.
 *
 * First launch persists a random 16-hex challenge. The matching 6-digit code is
 * HMAC-SHA256(secret, challenge) truncated like HOTP (RFC 4226). The same secret
 * lives in `scripts/alpha-unlock.py`. No network is involved.
 *
 * This is not a second independent factor: the verifier is in the APK. It stops
 * casual use of a leaked build. Anyone who reverse-engineers the APK can mint
 * codes. Keep the Python script off testers' devices.
 */
object AlphaGate {
    const val CHALLENGE_HEX_LEN = 16
    const val OTP_LEN = 6

    /** XOR mask; must match `scripts/alpha-unlock.py`. */
    private val MASK = intArrayOf(
        0x9A, 0x37, 0x19, 0xE4, 0x42, 0x1F, 0x24, 0xCD,
        0xA7, 0xF8, 0x51, 0x63, 0xCF, 0x92, 0x3F, 0x6C,
        0xC1, 0xAB, 0x6D, 0x63, 0xF1, 0x5B, 0xAB, 0x19,
        0x5E, 0x7B, 0xD4, 0xC9, 0xF8, 0xFC, 0xA2, 0x85,
    )

    /** `secret XOR MASK`; must match `scripts/alpha-unlock.py`. */
    private val OBFUSCATED = intArrayOf(
        0xF3, 0x10, 0x70, 0x94, 0x90, 0x3A, 0xEB, 0xB2,
        0x7B, 0x89, 0x3F, 0xE9, 0xFC, 0xE9, 0x18, 0xF2,
        0xD9, 0xB6, 0xFE, 0x2D, 0x31, 0xA2, 0xB6, 0x5C,
        0x2A, 0x69, 0xCE, 0x7A, 0x8E, 0xB5, 0x49, 0x41,
    )

    private val HEX16 = Regex("^[0-9a-f]{$CHALLENGE_HEX_LEN}$")

    fun newChallengeHex(): String {
        val bytes = ByteArray(CHALLENGE_HEX_LEN / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun normalizeChallenge(raw: String): String? {
        val compact = raw.trim().lowercase()
            .replace("-", "")
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
        return compact.takeIf { HEX16.matches(it) }
    }

    /** Grouped uppercase for the UI. Clipboard copies compact lowercase hex. */
    fun formatDisplay(hex: String): String {
        val n = normalizeChallenge(hex) ?: return hex
        return "${n.substring(0, 4)}-${n.substring(4, 8)}-${n.substring(8, 12)}-${n.substring(12, 16)}"
            .uppercase()
    }

    fun normalizeOtp(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return digits.takeIf { it.length == OTP_LEN }
    }

    /** Digits from a paste blob (spaces, dashes, surrounding text). */
    fun otpDigitsFromClipboard(raw: String?): String =
        raw.orEmpty().filter { it.isDigit() }.take(OTP_LEN)

    fun oneTimeCode(challengeHex: String): String {
        val challenge = requireNotNull(normalizeChallenge(challengeHex)) { "bad challenge" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes(), "HmacSHA256"))
        val hmac = mac.doFinal(challenge.toByteArray(Charsets.US_ASCII))
        val offset = hmac[hmac.lastIndex].toInt() and 0x0f
        val binary =
            ((hmac[offset].toInt() and 0x7f) shl 24) or
                ((hmac[offset + 1].toInt() and 0xff) shl 16) or
                ((hmac[offset + 2].toInt() and 0xff) shl 8) or
                (hmac[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(OTP_LEN, '0')
    }

    fun otpMatches(challengeHex: String, otpRaw: String): Boolean {
        val otp = normalizeOtp(otpRaw) ?: return false
        val challenge = normalizeChallenge(challengeHex) ?: return false
        val expected = oneTimeCode(challenge).toByteArray(Charsets.US_ASCII)
        val given = otp.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, given)
    }

    /** Consecutive wrong 6-digit guesses. 6 digits are brute-forceable in the UI. */
    fun lockMsAfterFails(fails: Int): Long = when {
        fails < 5 -> 0L
        fails < 8 -> 30_000L
        fails < 10 -> 120_000L
        else -> 900_000L
    }

    fun formatLockRemaining(remainingMs: Long): String {
        val sec = (remainingMs / 1_000L).coerceAtLeast(1L)
        return if (sec < 60L) {
            "$sec с"
        } else {
            val min = (sec + 59L) / 60L
            "$min мин"
        }
    }

    private fun secretBytes(): ByteArray = ByteArray(MASK.size) { i ->
        (OBFUSCATED[i] xor MASK[i]).toByte()
    }
}

sealed class AlphaUnlockResult {
    data object Success : AlphaUnlockResult()
    data class WrongCode(val fails: Int, val lockMs: Long) : AlphaUnlockResult()
    data class Locked(val remainingMs: Long) : AlphaUnlockResult()
}
