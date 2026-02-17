package tech.lenooby09.offlineAuth.config

import tech.lenooby09.offlineAuth.OfflineAuth
import java.nio.file.Files
import java.nio.file.Path

data class OfflineAuthConfig(
	val authTimeoutSeconds: Long = 60L,
	val softBanMinutes: Long = 5L,
	val maxLoginAttempts: Int = 5,
	val minPasswordLength: Int = 8,
	val skyY: Double = 30000.0,
	val autoAuthOps: Boolean = true,
	val inviteCodeLength: Int = 10,
	val sessionPersistenceEnabled: Boolean = false,
	val sessionDurationMinutes: Long = 1440L,
	val maxRegisterAttemptsPerIp: Int = 5,
	val registerCooldownSeconds: Long = 60L,
	val maxAccountsPerIp: Int = 3,
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
			"session-persistence-enabled" to "# Whether players stay authenticated across reconnects from the same IP",
			"session-duration-minutes" to "# How long a session persists in minutes (default: 1440 = 24 hours)",
			"max-register-attempts-per-ip" to "# Maximum registration attempts per IP before cooldown kicks in",
			"register-cooldown-seconds" to "# Cooldown in seconds after max registration attempts from the same IP",
			"max-accounts-per-ip" to "# Maximum number of accounts that can be registered from a single IP address (0 = unlimited)",
		)

		fun load(configDir: Path): OfflineAuthConfig {
			val file = configDir.resolve(FILE_NAME)

			if (!Files.exists(file)) {
				val default = OfflineAuthConfig()
				default.save(configDir)
				OfflineAuth.LOGGER.info("Generated default config file at $file")
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

				OfflineAuthConfig(
					authTimeoutSeconds = values["auth-timeout-seconds"]?.toLongOrNull() ?: 60L,
					softBanMinutes = values["soft-ban-minutes"]?.toLongOrNull() ?: 5L,
					maxLoginAttempts = values["max-login-attempts"]?.toIntOrNull() ?: 5,
					minPasswordLength = values["min-password-length"]?.toIntOrNull() ?: 8,
					skyY = values["sky-y"]?.toDoubleOrNull() ?: 30000.0,
					autoAuthOps = values["auto-auth-ops"]?.toBooleanStrictOrNull() ?: true,
					inviteCodeLength = values["invite-code-length"]?.toIntOrNull()?.coerceAtLeast(4) ?: 10,
					sessionPersistenceEnabled = values["session-persistence-enabled"]?.toBooleanStrictOrNull() ?: false,
					sessionDurationMinutes = values["session-duration-minutes"]?.toLongOrNull() ?: 1440L,
					maxRegisterAttemptsPerIp = values["max-register-attempts-per-ip"]?.toIntOrNull() ?: 5,
					registerCooldownSeconds = values["register-cooldown-seconds"]?.toLongOrNull() ?: 60L,
					maxAccountsPerIp = values["max-accounts-per-ip"]?.toIntOrNull() ?: 3,
				)
			} catch (e: Exception) {
				OfflineAuth.LOGGER.error("Failed to load config, using defaults", e)
				OfflineAuthConfig()
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
			"session-persistence-enabled" to sessionPersistenceEnabled.toString(),
			"session-duration-minutes" to sessionDurationMinutes.toString(),
			"max-register-attempts-per-ip" to maxRegisterAttemptsPerIp.toString(),
			"register-cooldown-seconds" to registerCooldownSeconds.toString(),
			"max-accounts-per-ip" to maxAccountsPerIp.toString(),
		)

		val builder = StringBuilder()
		builder.appendLine("# OfflineAuth Configuration")
		builder.appendLine("# Changes can be hot-reloaded with /offlineauth reload")
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
