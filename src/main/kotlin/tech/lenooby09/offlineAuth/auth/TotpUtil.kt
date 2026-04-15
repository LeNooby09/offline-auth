package tech.lenooby09.offlineAuth.auth

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (Time-based One-Time Password) implementation following RFC 6238.
 * Uses HMAC-SHA1 with 6-digit codes and 30-second time steps.
 */
object TotpUtil {

	private const val DIGITS = 6
	private const val TIME_STEP_SECONDS = 30L
	private const val SECRET_LENGTH = 20 // 160-bit secret
	private const val ALGORITHM = "HmacSHA1"

	// Base32 alphabet (RFC 4648)
	private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

	/**
	 * Generates a new random TOTP secret encoded as Base32.
	 */
	fun generateSecret(): String {
		val bytes = ByteArray(SECRET_LENGTH)
		SecureRandom().nextBytes(bytes)
		return base32Encode(bytes)
	}

	/**
	 * Generates a TOTP code for the given secret at the current time.
	 */
	fun generateCode(base32Secret: String): String {
		return generateCode(base32Secret, System.currentTimeMillis())
	}

	/**
	 * Generates a TOTP code for the given secret at a specific timestamp.
	 */
	fun generateCode(base32Secret: String, timeMillis: Long): String {
		val key = base32Decode(base32Secret)
		val timeCounter = timeMillis / 1000 / TIME_STEP_SECONDS
		val data = ByteArray(8)
		var value = timeCounter
		for (i in 7 downTo 0) {
			data[i] = (value and 0xFF).toByte()
			value = value shr 8
		}

		val mac = Mac.getInstance(ALGORITHM)
		mac.init(SecretKeySpec(key, ALGORITHM))
		val hash = mac.doFinal(data)

		val offset = (hash[hash.size - 1].toInt() and 0x0F)
		val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
			((hash[offset + 1].toInt() and 0xFF) shl 16) or
			((hash[offset + 2].toInt() and 0xFF) shl 8) or
			(hash[offset + 3].toInt() and 0xFF)

		val otp = binary % Math.pow(10.0, DIGITS.toDouble()).toInt()
		return otp.toString().padStart(DIGITS, '0')
	}

	/**
	 * Verifies a TOTP code against the secret, allowing a window of ±1 time step
	 * to account for clock drift.
	 */
	fun verifyCode(base32Secret: String, code: String, window: Int = 1): Boolean {
		val now = System.currentTimeMillis()
		for (i in -window..window) {
			val timeMillis = now + (i * TIME_STEP_SECONDS * 1000)
			if (generateCode(base32Secret, timeMillis) == code.padStart(DIGITS, '0')) {
				return true
			}
		}
		return false
	}

	/**
	 * Generates a otpauth:// URI for use with authenticator apps.
	 */
	fun generateOtpAuthUri(secret: String, username: String, issuer: String = "OfflineAuth"): String {
		val encodedLabel = urlEncode("$issuer:$username")
		return "otpauth://totp/$encodedLabel?secret=$secret"
	}

	private fun urlEncode(value: String): String {
		return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
	}

	private fun base32Encode(data: ByteArray): String {
		val result = StringBuilder()
		var buffer = 0
		var bitsLeft = 0

		for (byte in data) {
			buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
			bitsLeft += 8
			while (bitsLeft >= 5) {
				val index = (buffer shr (bitsLeft - 5)) and 0x1F
				result.append(BASE32_CHARS[index])
				bitsLeft -= 5
			}
		}
		if (bitsLeft > 0) {
			val index = (buffer shl (5 - bitsLeft)) and 0x1F
			result.append(BASE32_CHARS[index])
		}
		return result.toString()
	}

	private fun base32Decode(encoded: String): ByteArray {
		val cleaned = encoded.uppercase().replace("-", "").replace(" ", "")
		val result = mutableListOf<Byte>()
		var buffer = 0
		var bitsLeft = 0

		for (char in cleaned) {
			val value = BASE32_CHARS.indexOf(char)
			if (value < 0) continue
			buffer = (buffer shl 5) or value
			bitsLeft += 5
			if (bitsLeft >= 8) {
				result.add((buffer shr (bitsLeft - 8) and 0xFF).toByte())
				bitsLeft -= 8
			}
		}
		return result.toByteArray()
	}
}
