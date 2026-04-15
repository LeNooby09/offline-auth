package tech.lenooby09.offlineAuth.auth

import io.nayuki.qrcodegen.QrCode
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom

object QrCodeUtil {

	private const val SCALE = 8
	private const val BORDER = 4

	// Temporary one-time tokens mapping to PNG bytes
	private val pendingQrImages = ConcurrentHashMap<String, ByteArray>()

	private val random = SecureRandom()

	/**
	 * Generates a QR code PNG image for the given data, stores it with a one-time token,
	 * and returns the token. Returns null if generation fails.
	 */
	fun storeQrImage(data: String): String? {
		return try {
			val qr = QrCode.encodeText(data, QrCode.Ecc.LOW)
			val png = renderToPng(qr)
			val token = generateToken()
			pendingQrImages[token] = png
			token
		} catch (e: Exception) {
			null
		}
	}

	/**
	 * Retrieves and removes a stored QR image by token (one-time use).
	 */
	fun consumeQrImage(token: String): ByteArray? {
		return pendingQrImages.remove(token)
	}

	private fun generateToken(): String {
		val bytes = ByteArray(24)
		random.nextBytes(bytes)
		return bytes.joinToString("") { "%02x".format(it) }
	}

	/**
	 * Renders a QR code to a minimal PNG byte array (no external image libraries needed).
	 */
	private fun renderToPng(qr: QrCode): ByteArray {
		val size = qr.size
		val imgSize = (size + BORDER * 2) * SCALE

		// Build raw RGBA pixel rows, then encode as PNG
		val baos = ByteArrayOutputStream()
		val png = PngWriter(baos, imgSize, imgSize)

		for (py in 0 until imgSize) {
			val row = ByteArray(imgSize)
			for (px in 0 until imgSize) {
				val mx = px / SCALE - BORDER
				val my = py / SCALE - BORDER
				val isDark = mx in 0 until size && my in 0 until size && qr.getModule(mx, my)
				row[px] = if (isDark) 0x00.toByte() else 0xFF.toByte()
			}
			png.writeRow(row)
		}

		png.finish()
		return baos.toByteArray()
	}

	/**
	 * Minimal PNG encoder for grayscale images (no dependencies).
	 */
	private class PngWriter(
		private val out: ByteArrayOutputStream,
		private val width: Int,
		private val height: Int,
	) {
		private val deflaterStream: java.util.zip.DeflaterOutputStream
		private val idatBuffer = ByteArrayOutputStream()

		init {
			// PNG signature
			out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

			// IHDR chunk
			val ihdr = ByteArrayOutputStream()
			ihdr.writeInt(width)
			ihdr.writeInt(height)
			ihdr.write(8)  // bit depth
			ihdr.write(0)  // color type: grayscale
			ihdr.write(0)  // compression
			ihdr.write(0)  // filter
			ihdr.write(0)  // interlace
			writeChunk("IHDR", ihdr.toByteArray())

			deflaterStream = java.util.zip.DeflaterOutputStream(idatBuffer)
		}

		fun writeRow(row: ByteArray) {
			deflaterStream.write(0) // filter byte: None
			deflaterStream.write(row)
		}

		fun finish() {
			deflaterStream.finish()
			deflaterStream.close()
			writeChunk("IDAT", idatBuffer.toByteArray())
			writeChunk("IEND", ByteArray(0))
		}

		private fun writeChunk(type: String, data: ByteArray) {
			val lengthBytes = ByteArray(4)
			lengthBytes[0] = (data.size shr 24).toByte()
			lengthBytes[1] = (data.size shr 16).toByte()
			lengthBytes[2] = (data.size shr 8).toByte()
			lengthBytes[3] = data.size.toByte()
			out.write(lengthBytes)

			val typeBytes = type.toByteArray(Charsets.US_ASCII)
			out.write(typeBytes)
			out.write(data)

			val crc = java.util.zip.CRC32()
			crc.update(typeBytes)
			crc.update(data)
			val crcVal = crc.value.toInt()
			val crcBytes = ByteArray(4)
			crcBytes[0] = (crcVal shr 24).toByte()
			crcBytes[1] = (crcVal shr 16).toByte()
			crcBytes[2] = (crcVal shr 8).toByte()
			crcBytes[3] = crcVal.toByte()
			out.write(crcBytes)
		}

		private fun ByteArrayOutputStream.writeInt(v: Int) {
			write((v shr 24) and 0xFF)
			write((v shr 16) and 0xFF)
			write((v shr 8) and 0xFF)
			write(v and 0xFF)
		}
	}
}
