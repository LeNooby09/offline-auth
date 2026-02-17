package tech.lenooby09.offlineAuth.auth

import java.util.*

data class AuthAccount(
	val id: UUID,
	val username: String,
	val passwordHash: String,
	val registeredAt: Long,
)
