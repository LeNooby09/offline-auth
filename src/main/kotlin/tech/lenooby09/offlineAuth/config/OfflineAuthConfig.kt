package tech.lenooby09.offlineAuth.config

import tech.lenooby09.offlineAuth.OfflineAuth
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
	val loginLockoutBaseSeconds: Long = 30L,
	val loginLockoutMaxSeconds: Long = 3600L,
	val hideJoinMessageUntilLogin: Boolean = false,
	val webDashboardEnabled: Boolean = false,
	val webDashboardPort: Int = 8080,
	val webDashboardBindAddress: String = "127.0.0.1",
	val webDashboardDomain: String = "",
	val databaseType: String = "sqlite",
	val postgresHost: String = "localhost",
	val postgresPort: Int = 5432,
	val postgresDatabase: String = "offlineauth",
	val postgresUser: String = "offlineauth",
	val postgresPassword: String = "",
	// Bluesky (ATProto) opt-in auth: when enabled the legacy /register, /login, /login_as,
	// /changepassword, /2fa, and the invite-code admin subcommands are runtime-disabled and
	// players authenticate via /bluesky instead. Defaults below leave the mod in password mode.
	val blueskyEnabled: Boolean = false,
	val blueskyWhitelistList: String = "",
	val blueskyPublicUrl: String = "",
	val blueskyClientName: String = "OfflineAuth Minecraft Server",
	val blueskyScope: String = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
	val blueskyListCacheSeconds: Long = 300L,
	val blueskyPairingTokenTtlMinutes: Long = 10L,
) {

	/**
	 * Stable, content-derived version tag for the published OAuth client
	 * metadata document. Empty when no public URL is configured.
	 *
	 * Authorization Servers running `@atproto/oauth-provider` cache the
	 * client metadata document keyed by `client_id` URL (default TTL: 10
	 * minutes; see `clientMetadataCache` in `oauth-provider`). When operators
	 * tweak Bluesky-related config (e.g. `bluesky-scope`,
	 * `bluesky-client-name`) they would otherwise be hit by stale-cache
	 * errors such as `invalid_scope: Scope "..." is not declared in the
	 * client metadata` until the TTL expires. By embedding a hash of the
	 * metadata-relevant fields in the published `client_id` URL we force
	 * PDSes to treat any change as a brand-new client and re-fetch the
	 * document, while keeping the URL stable across restarts when nothing
	 * relevant has changed.
	 */
	val blueskyClientMetadataVersion: String
		get() {
			if (blueskyPublicUrl.isBlank()) return ""
			val content = listOf(
				blueskyScope,
				blueskyClientName,
				blueskyPublicUrl.trimEnd('/'),
			).joinToString("|")
			val digest = MessageDigest.getInstance("SHA-256")
				.digest(content.toByteArray(Charsets.UTF_8))
			return digest.joinToString("") { "%02x".format(it) }.take(8)
		}

	/**
	 * Derived URL — never stored — used as the OAuth `client_id`.
	 *
	 * Includes a content-derived `?v=<hash>` query param so that PDSes which
	 * cache client metadata by `client_id` (default 10 min in
	 * `@atproto/oauth-provider`) bypass the cache the moment the operator
	 * changes any metadata-relevant field. See [blueskyClientMetadataVersion].
	 */
	val blueskyClientId: String
		get() {
			val base = "${blueskyPublicUrl.trimEnd('/')}/oauth-client-metadata.json"
			val version = blueskyClientMetadataVersion
			return if (version.isEmpty()) base else "$base?v=$version"
		}

	/** Derived URL — never stored — used as the OAuth `redirect_uri`. */
	val blueskyRedirectUri: String
		get() = "${blueskyPublicUrl.trimEnd('/')}/bluesky/callback"

	/**
	 * Validates the Bluesky-related config keys. Returns `false` and emits warnings via
	 * [OfflineAuth.LOGGER] when any required field is missing or insecure, so the caller
	 * can fall back to password mode and keep the server usable.
	 */
	fun validateBlueskyConfig(): Boolean {
		if (!blueskyEnabled) return false
		var ok = true
		if (blueskyPublicUrl.isBlank()) {
			OfflineAuth.LOGGER.warn(
				"[Bluesky] bluesky-enabled is true but bluesky-public-url is empty — falling back to password mode."
			)
			ok = false
		} else {
			val lower = blueskyPublicUrl.lowercase()
			val isHttps = lower.startsWith("https://")
			val isLoopback = lower.startsWith("http://127.0.0.1") ||
				lower.startsWith("http://localhost") ||
				lower.startsWith("http://[::1]")
			if (!isHttps && !isLoopback) {
				OfflineAuth.LOGGER.warn(
					"[Bluesky] bluesky-public-url is not HTTPS (and not a loopback address) — " +
						"ATProto rejects non-loopback http client_ids. Falling back to password mode."
				)
				ok = false
			}
		}
		if (blueskyWhitelistList.isBlank()) {
			OfflineAuth.LOGGER.warn(
				"[Bluesky] bluesky-enabled is true but bluesky-whitelist-list is empty — falling back to password mode."
			)
			ok = false
		}
		// `transition:generic` is the deprecated legacy ATProto OAuth grant grammar. It is rejected at
		// startup so misconfigured upgrades fall back to password mode (loud-but-graceful) instead of
		// silently pinning the deployment to the deprecated grant. Detection is whole-token,
		// case-insensitive — substring matches inside unrelated tokens are not flagged.
		val scopeTokens = blueskyScope.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (scopeTokens.any { it.equals("transition:generic", ignoreCase = true) }) {
			OfflineAuth.LOGGER.warn(
				"[Bluesky] bluesky-scope contains the deprecated 'transition:generic' grant; " +
					"replace it with 'atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview' " +
					"(or another rpc:... lexicon-permission scope) and restart. Falling back to password mode."
			)
			ok = false
		}
		return ok
	}

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
			"login-lockout-base-seconds" to "# Base lockout duration in seconds after max failed login attempts (doubles each time)",
			"login-lockout-max-seconds" to "# Maximum lockout duration in seconds (cap for exponential backoff)",
			"hide-join-message-until-login" to "# Whether to hide the join message until the player authenticates (default: false)",
			"web-dashboard-enabled" to "# Whether to enable the embedded web dashboard for account management",
			"web-dashboard-port" to "# Port for the web dashboard HTTP server",
			"web-dashboard-bind-address" to "# Bind address for the web dashboard (127.0.0.1 = localhost only, 0.0.0.0 = all interfaces)",
			"web-dashboard-domain" to "# Public domain/IP for QR code URLs (e.g. 'example.com'). If empty, uses the bind address.",
			"database-type" to "# Database backend to use: 'sqlite' (local file) or 'postgresql' (remote server for multi-server setups)",
			"postgres-host" to "# PostgreSQL server hostname (only used when database-type is 'postgresql')",
			"postgres-port" to "# PostgreSQL server port",
			"postgres-database" to "# PostgreSQL database name",
			"postgres-user" to "# PostgreSQL username",
			"postgres-password" to "# PostgreSQL password",
			"bluesky-enabled" to "# Opt-in: enable Bluesky (ATProto) OAuth login. When true, legacy password commands are runtime-disabled and players use /bluesky.",
			"bluesky-whitelist-list" to "# Bluesky list reference (an at://... URI or a https://bsky.app/profile/<handle-or-did>/lists/<rkey> URL). Members of this list may log in.",
			"bluesky-public-url" to "# Public HTTPS base URL the OAuth callback is reachable at (e.g. https://auth.example.com). Loopback http://127.0.0.1 is allowed for dev only.",
			"bluesky-client-name" to "# Display name shown to users on the Bluesky consent screen",
			"bluesky-scope" to "# OAuth scopes requested. Must include 'atproto'. Default 'atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview' uses the modern lexicon-permission grammar (only the AppView proxy call OfflineAuth needs). The legacy 'transition:generic' grant is deprecated and rejected at startup; if your scope still contains it the server falls back to password mode and logs a migration hint. Operators stuck on an older PDS that only understands 'transition:generic' should pin a previous OfflineAuth release.",
			"bluesky-list-cache-seconds" to "# How long to cache the Bluesky list members before re-fetching (default: 300 = 5 minutes)",
			"bluesky-pairing-token-ttl-minutes" to "# How long an in-game /bluesky pairing token stays valid before it expires (default: 10 minutes)",
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
					loginLockoutBaseSeconds = values["login-lockout-base-seconds"]?.toLongOrNull() ?: 30L,
					loginLockoutMaxSeconds = values["login-lockout-max-seconds"]?.toLongOrNull() ?: 3600L,
					hideJoinMessageUntilLogin = values["hide-join-message-until-login"]?.toBooleanStrictOrNull() ?: false,
					webDashboardEnabled = values["web-dashboard-enabled"]?.toBooleanStrictOrNull() ?: false,
					webDashboardPort = values["web-dashboard-port"]?.toIntOrNull() ?: 8080,
					webDashboardBindAddress = values["web-dashboard-bind-address"] ?: "127.0.0.1",
					webDashboardDomain = values["web-dashboard-domain"] ?: "",
					databaseType = values["database-type"] ?: "sqlite",
					postgresHost = values["postgres-host"] ?: "localhost",
					postgresPort = values["postgres-port"]?.toIntOrNull() ?: 5432,
					postgresDatabase = values["postgres-database"] ?: "offlineauth",
					postgresUser = values["postgres-user"] ?: "offlineauth",
					postgresPassword = values["postgres-password"] ?: "",
					blueskyEnabled = values["bluesky-enabled"]?.toBooleanStrictOrNull() ?: false,
					blueskyWhitelistList = values["bluesky-whitelist-list"] ?: "",
					blueskyPublicUrl = values["bluesky-public-url"] ?: "",
					blueskyClientName = values["bluesky-client-name"] ?: "OfflineAuth Minecraft Server",
					blueskyScope = values["bluesky-scope"] ?: "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
					blueskyListCacheSeconds = values["bluesky-list-cache-seconds"]?.toLongOrNull() ?: 300L,
					blueskyPairingTokenTtlMinutes = values["bluesky-pairing-token-ttl-minutes"]?.toLongOrNull() ?: 10L,
				)
			} catch (e: Exception) {
				OfflineAuth.LOGGER.error("Failed to load config, using defaults", e)
				OfflineAuthConfig()
			}.also {
				// Re-save to add any new config options that didn't exist in the file
				it.save(configDir)
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
			"login-lockout-base-seconds" to loginLockoutBaseSeconds.toString(),
			"login-lockout-max-seconds" to loginLockoutMaxSeconds.toString(),
			"hide-join-message-until-login" to hideJoinMessageUntilLogin.toString(),
			"web-dashboard-enabled" to webDashboardEnabled.toString(),
			"web-dashboard-port" to webDashboardPort.toString(),
			"web-dashboard-bind-address" to webDashboardBindAddress,
			"web-dashboard-domain" to webDashboardDomain,
			"database-type" to databaseType,
			"postgres-host" to postgresHost,
			"postgres-port" to postgresPort.toString(),
			"postgres-database" to postgresDatabase,
			"postgres-user" to postgresUser,
			"postgres-password" to postgresPassword,
			"bluesky-enabled" to blueskyEnabled.toString(),
			"bluesky-whitelist-list" to blueskyWhitelistList,
			"bluesky-public-url" to blueskyPublicUrl,
			"bluesky-client-name" to blueskyClientName,
			"bluesky-scope" to blueskyScope,
			"bluesky-list-cache-seconds" to blueskyListCacheSeconds.toString(),
			"bluesky-pairing-token-ttl-minutes" to blueskyPairingTokenTtlMinutes.toString(),
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
