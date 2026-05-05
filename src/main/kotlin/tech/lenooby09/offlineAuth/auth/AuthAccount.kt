package tech.lenooby09.offlineAuth.auth

import java.util.*

data class AuthAccount(
	val id: UUID,
	val username: String,
	/**
	 * BCrypt hash of the player's password, or `null` for accounts created via Bluesky OAuth
	 * (where authentication is delegated to the ATProto identity provider). Password-mode code
	 * paths must assert non-null on read.
	 */
	val passwordHash: String?,
	val registeredAt: Long,
	val isDashboardAdmin: Boolean = false,
)

/**
 * A persisted link between a Minecraft account ([accountId]) and a Bluesky/ATProto identity.
 * Stored in the `bluesky_links` table; absence means the account is password-mode only.
 */
data class BlueskyLink(
	val accountId: UUID,
	val did: String,
	val handle: String,
	val avatarUrl: String?,
	val linkedAt: Long,
)
