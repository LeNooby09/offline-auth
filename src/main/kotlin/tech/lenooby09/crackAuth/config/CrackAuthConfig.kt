package tech.lenooby09.crackAuth.config

import tech.lenooby09.crackAuth.CrackAuth
import java.nio.file.Files
import java.nio.file.Path

data class CrackAuthConfig(
	val authTimeoutSeconds: Long = 60L,
	val softBanMinutes: Long = 5L,
	val maxLoginAttempts: Int = 5,
	val minPasswordLength: Int = 8,
	val skyY: Double = 30000.0,
	val autoAuthOps: Boolean = true,
	val inviteCodeLength: Int = 10,
) {

	companion object {

		private const val FILE_NAME = "config.yml"

		private val COMMENTS = mapOf(
			"auth-timeout-seconds" to "# How many seconds a player has to authenticate before being kicked",
			"soft-ban-minutes" to "# How many minutes a player is temporarily banned after auth timeout",
			"max-login-attempts" to "# Maximum failed login attempts before the player is kicked",
			"min-password-length" to "# Minimum password length required for registration",
			"sky-y" to "# Y coordinate where unauthenticated players are held in the sky",
			"auto-auth-ops" to "# Whether server operators (OPs) are automatically authenticated on join",
			"invite-code-length" to "# Length of generated invite codes (number of alphanumeric characters, excluding dashes)",
		)

		fun load(configDir: Path): CrackAuthConfig {
			val file = configDir.resolve(FILE_NAME)

			if (!Files.exists(file)) {
				val default = CrackAuthConfig()
				default.save(configDir)
				CrackAuth.LOGGER.info("Generated default config file at $file")
				return default
			}

			return try {
				val lines = Files.readAllLines(file)
				val values = mutableMapOf<String, String>()

				for (line in lines) {
					val trimmed = line.trim()
					if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
					val colonIndex = trimmed.indexOf(':')
					if (colonIndex < 0) continue
					val key = trimmed.substring(0, colonIndex).trim()
					val value = trimmed.substring(colonIndex + 1).trim()
					values[key] = value
				}

				CrackAuthConfig(
					authTimeoutSeconds = values["auth-timeout-seconds"]?.toLongOrNull() ?: 60L,
					softBanMinutes = values["soft-ban-minutes"]?.toLongOrNull() ?: 5L,
					maxLoginAttempts = values["max-login-attempts"]?.toIntOrNull() ?: 5,
					minPasswordLength = values["min-password-length"]?.toIntOrNull() ?: 8,
					skyY = values["sky-y"]?.toDoubleOrNull() ?: 30000.0,
					autoAuthOps = values["auto-auth-ops"]?.toBooleanStrictOrNull() ?: true,
					inviteCodeLength = values["invite-code-length"]?.toIntOrNull()?.coerceAtLeast(4) ?: 10,
				)
			} catch (e: Exception) {
				CrackAuth.LOGGER.error("Failed to load config, using defaults", e)
				CrackAuthConfig()
			}
		}
	}

	fun save(configDir: Path) {
		val file = configDir.resolve(FILE_NAME)
		Files.createDirectories(configDir)

		val entries = listOf(
			"auth-timeout-seconds" to authTimeoutSeconds.toString(),
			"soft-ban-minutes" to softBanMinutes.toString(),
			"max-login-attempts" to maxLoginAttempts.toString(),
			"min-password-length" to minPasswordLength.toString(),
			"sky-y" to skyY.toString(),
			"auto-auth-ops" to autoAuthOps.toString(),
			"invite-code-length" to inviteCodeLength.toString(),
		)

		val builder = StringBuilder()
		builder.appendLine("# CrackAuth Configuration")
		builder.appendLine("# Changes to this file require a server restart to take effect.")
		builder.appendLine()

		for ((key, value) in entries) {
			val comment = COMMENTS[key]
			if (comment != null) {
				builder.appendLine(comment)
			}
			builder.appendLine("$key: $value")
			builder.appendLine()
		}

		Files.writeString(file, builder.toString())
	}
}
