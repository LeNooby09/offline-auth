package tech.lenooby09.offlineAuth.web

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.lenooby09.offlineAuth.OfflineAuth
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.commands.AdminCommands
import tech.lenooby09.offlineAuth.config.OfflineAuthConfig
import tech.lenooby09.offlineAuth.storage.DatabaseManager
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class WebDashboard(
	private val database: DatabaseManager,
	private val authManager: AuthManager,
	private val config: OfflineAuthConfig,
) {

	private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

	private val sessions = ConcurrentHashMap<String, DashboardSession>()

	data class DashboardSession(val username: String, val isAdmin: Boolean, val createdAt: Long)

	fun start() {
		val bindAddress = config.webDashboardBindAddress
		val port = config.webDashboardPort

		server = embeddedServer(CIO, port = port, host = bindAddress) {
			configureSerialization()
			configureCORS()
			configureRouting()
		}.also {
			it.start(wait = false)
		}

		OfflineAuth.LOGGER.info("Web dashboard started on http://$bindAddress:$port")
	}

	fun stop() {
		server?.stop(1000, 2000)
		server = null
		sessions.clear()
		OfflineAuth.LOGGER.info("Web dashboard stopped.")
	}

	private fun Application.configureSerialization() {
		install(ContentNegotiation) {
			json(Json {
				prettyPrint = false
				ignoreUnknownKeys = true
			})
		}
	}

	private fun Application.configureCORS() {
		install(CORS) {
			anyHost()
			allowMethod(HttpMethod.Get)
			allowMethod(HttpMethod.Post)
			allowMethod(HttpMethod.Put)
			allowMethod(HttpMethod.Delete)
			allowMethod(HttpMethod.Options)
			allowHeader(HttpHeaders.ContentType)
			allowHeader(HttpHeaders.Authorization)
		}
	}

	private fun Application.configureRouting() {
		routing {
			get("/") {
				call.respondText(DashboardHtml.INDEX, ContentType.Text.Html)
			}

			// --- QR code image endpoint (one-time token, no auth required) ---
			get("/qr/{token}") {
				val token = call.parameters["token"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing token."))
					return@get
				}
				val png = tech.lenooby09.offlineAuth.auth.QrCodeUtil.consumeQrImage(token)
				if (png == null) {
					call.respond(HttpStatusCode.NotFound, ErrorResponse("QR code not found or already used."))
					return@get
				}
				call.respondBytes(png, ContentType.Image.PNG)
			}

			// --- Auth endpoints ---
			post("/api/login") {
				val request = call.receive<LoginRequest>()
				val account = database.getAccountByUsername(request.username)

				if (account == null) {
					call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password."))
					return@post
				}

				val result = BCrypt.verifyer().verify(request.password.toCharArray(), account.passwordHash)
				if (!result.verified) {
					call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password."))
					return@post
				}

				val token = generateToken()
				sessions[token] = DashboardSession(account.username, account.isDashboardAdmin, System.currentTimeMillis())

				call.respond(LoginResponse(token, account.username, account.isDashboardAdmin))
			}

			post("/api/logout") {
				val token = call.extractToken()
				if (token != null) {
					sessions.remove(token)
				}
				call.respond(SuccessResponse("Logged out."))
			}

			get("/api/me") {
				val session = call.requireAuth() ?: return@get
				call.respond(MeResponse(session.username, session.isAdmin))
			}

			// --- Stats endpoint (available to all authenticated users) ---
			get("/api/stats") {
				call.requireAuth() ?: return@get
				val totalAccounts = database.getAllAccounts().size
				val activeSessions = database.getActiveSessions().size
				val activeBans = database.getActiveSoftBans().size
				val activeInvites = database.getActiveInviteCodes().size
				val onlinePlayers = authManager.authStates.count { it.value == tech.lenooby09.offlineAuth.auth.AuthState.AUTHENTICATED }

				call.respond(StatsResponse(
					totalAccounts = totalAccounts,
					activeSessions = activeSessions,
					activeBans = activeBans,
					activeInvites = activeInvites,
					onlinePlayers = onlinePlayers,
				))
			}

			// --- Admin-only endpoints below ---

			get("/api/accounts") {
				val session = call.requireAdmin() ?: return@get
				val accounts = database.getAllAccounts().map { acc ->
					AccountResponse(
						id = acc.id.toString(),
						username = acc.username,
						registeredAt = acc.registeredAt,
						linkedUUIDs = database.getLinkedUUIDs(acc.id),
						isDashboardAdmin = acc.isDashboardAdmin,
						has2fa = database.has2faEnabled(acc.id),
					)
				}
				call.respond(accounts)
			}

			get("/api/accounts/{id}") {
				call.requireAdmin() ?: return@get
				val id = call.parameters["id"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing account ID."))
					return@get
				}
				val accountId = try { UUID.fromString(id) } catch (_: Exception) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid account ID."))
					return@get
				}

				val accounts = database.getAllAccounts()
				val account = accounts.find { it.id == accountId }
				if (account == null) {
					call.respond(HttpStatusCode.NotFound, ErrorResponse("Account not found."))
					return@get
				}

				call.respond(
					AccountResponse(
						id = account.id.toString(),
						username = account.username,
						registeredAt = account.registeredAt,
						linkedUUIDs = database.getLinkedUUIDs(account.id),
						isDashboardAdmin = account.isDashboardAdmin,
						has2fa = database.has2faEnabled(account.id),
					)
				)
			}

			post("/api/accounts") {
				call.requireAdmin() ?: return@post
				val request = call.receive<CreateAccountRequest>()

				if (request.username.length !in 3..16) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username must be between 3 and 16 characters."))
					return@post
				}
				if (!request.username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username can only contain letters, numbers, and underscores."))
					return@post
				}
				if (request.password.length < config.minPasswordLength) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least ${config.minPasswordLength} characters."))
					return@post
				}
				if (database.getAccountByUsername(request.username) != null) {
					call.respond(HttpStatusCode.Conflict, ErrorResponse("Username already taken."))
					return@post
				}

				val account = tech.lenooby09.offlineAuth.auth.AuthAccount(
					id = UUID.randomUUID(),
					username = request.username,
					passwordHash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray()),
					registeredAt = System.currentTimeMillis(),
				)
				database.saveAccount(account)

				call.respond(HttpStatusCode.Created, AccountResponse(
					id = account.id.toString(),
					username = account.username,
					registeredAt = account.registeredAt,
					linkedUUIDs = emptyList(),
					isDashboardAdmin = account.isDashboardAdmin,
					has2fa = false,
				))
			}

			delete("/api/accounts/{id}") {
				call.requireAdmin() ?: return@delete
				val id = call.parameters["id"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing account ID."))
					return@delete
				}

				val accounts = database.getAllAccounts()
				val account = accounts.find { it.id.toString() == id }
				if (account == null) {
					call.respond(HttpStatusCode.NotFound, ErrorResponse("Account not found."))
					return@delete
				}

				val success = database.deleteAccountByUsername(account.username)
				if (success) {
					call.respond(SuccessResponse("Account '${account.username}' deleted."))
				} else {
					call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to delete account."))
				}
			}

			put("/api/accounts/{id}/rename") {
				call.requireAdmin() ?: return@put
				val id = call.parameters["id"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing account ID."))
					return@put
				}
				val request = call.receive<RenameRequest>()

				if (request.newUsername.length !in 3..16) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username must be between 3 and 16 characters."))
					return@put
				}
				if (!request.newUsername.matches(Regex("^[a-zA-Z0-9_]+$"))) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username can only contain letters, numbers, and underscores."))
					return@put
				}
				if (database.getAccountByUsername(request.newUsername) != null) {
					call.respond(HttpStatusCode.Conflict, ErrorResponse("Username already taken."))
					return@put
				}

				val accountId = try { UUID.fromString(id) } catch (_: Exception) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid account ID."))
					return@put
				}

				val success = database.updateUsername(accountId, request.newUsername)
				if (success) {
					call.respond(SuccessResponse("Account renamed to '${request.newUsername}'."))
				} else {
					call.respond(HttpStatusCode.NotFound, ErrorResponse("Account not found."))
				}
			}

			put("/api/accounts/{id}/password") {
				call.requireAdmin() ?: return@put
				val id = call.parameters["id"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing account ID."))
					return@put
				}
				val request = call.receive<ChangePasswordRequest>()

				if (request.newPassword.length < config.minPasswordLength) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least ${config.minPasswordLength} characters."))
					return@put
				}

				val accountId = try { UUID.fromString(id) } catch (_: Exception) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid account ID."))
					return@put
				}

				val hash = BCrypt.withDefaults().hashToString(12, request.newPassword.toCharArray())
				database.updatePasswordHash(accountId, hash)
				call.respond(SuccessResponse("Password updated."))
			}

			put("/api/accounts/{id}/reset2fa") {
				call.requireAdmin() ?: return@put
				val id = call.parameters["id"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing account ID."))
					return@put
				}

				val accountId = try { UUID.fromString(id) } catch (_: Exception) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid account ID."))
					return@put
				}

				if (!database.has2faEnabled(accountId)) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("2FA is not enabled for this account."))
					return@put
				}

				database.setTotpSecret(accountId, null)
				call.respond(SuccessResponse("2FA has been reset."))
			}

			put("/api/accounts/{id}/admin") {
				call.requireAdmin() ?: return@put
				val id = call.parameters["id"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing account ID."))
					return@put
				}
				val request = call.receive<SetAdminRequest>()

				val accountId = try { UUID.fromString(id) } catch (_: Exception) {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid account ID."))
					return@put
				}

				database.setDashboardAdmin(accountId, request.isAdmin)
				call.respond(SuccessResponse(if (request.isAdmin) "Dashboard admin granted." else "Dashboard admin revoked."))
			}

			get("/api/invites") {
				call.requireAdmin() ?: return@get
				val codes = database.getActiveInviteCodes().map { code ->
					InviteCodeResponse(
						code = code.code,
						createdBy = code.createdBy,
						createdAt = code.createdAt,
						maxUses = code.maxUses,
						currentUses = code.currentUses,
					)
				}
				call.respond(codes)
			}

			post("/api/invites") {
				val session = call.requireAdmin() ?: return@post
				val request = call.receive<CreateInviteRequest>()
				val maxUses = request.maxUses.coerceAtLeast(1)

				val code = AdminCommands.generateInviteCode(config.inviteCodeLength)
				database.saveInviteCode(code, session.username, System.currentTimeMillis(), maxUses)

				call.respond(HttpStatusCode.Created, InviteCodeResponse(
					code = code,
					createdBy = session.username,
					createdAt = System.currentTimeMillis(),
					maxUses = maxUses,
					currentUses = 0,
				))
			}

			delete("/api/invites/{code}") {
				call.requireAdmin() ?: return@delete
				val code = call.parameters["code"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing invite code."))
					return@delete
				}

				val success = database.revokeInviteCode(code)
				if (success) {
					call.respond(SuccessResponse("Invite code revoked."))
				} else {
					call.respond(HttpStatusCode.NotFound, ErrorResponse("Invite code not found."))
				}
			}

			get("/api/sessions") {
				call.requireAdmin() ?: return@get
				val activeSessions = database.getActiveSessions().map { s ->
					SessionResponse(
						accountId = s.accountId,
						username = s.username,
						ipAddress = s.ipAddress,
						expiresAt = s.expiresAt,
					)
				}
				call.respond(activeSessions)
			}

			get("/api/bans") {
				call.requireAdmin() ?: return@get
				val bans = database.getActiveSoftBans().map { ban ->
					SoftBanResponse(
						ipAddress = ban.ipAddress,
						expiresAt = ban.expiresAt,
					)
				}
				call.respond(bans)
			}

			delete("/api/bans/{ip}") {
				call.requireAdmin() ?: return@delete
				val ip = call.parameters["ip"] ?: run {
					call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing IP address."))
					return@delete
				}

				database.removeSoftBan(ip)
				call.respond(SuccessResponse("Soft ban removed for $ip."))
			}

			post("/api/config/reload") {
				call.requireAdmin() ?: return@post
				val configDir = OfflineAuth.configDir
				if (configDir == null) {
					call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Config directory not available."))
					return@post
				}

				try {
					val newConfig = OfflineAuthConfig.load(configDir)
					authManager.config = newConfig
					OfflineAuth.config = newConfig
					call.respond(SuccessResponse("Config reloaded successfully."))
				} catch (e: Exception) {
					call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to reload config: ${e.message}"))
				}
			}
		}
	}

	private suspend fun ApplicationCall.requireAuth(): DashboardSession? {
		val token = extractToken()
		if (token == null) {
			respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required."))
			return null
		}
		val session = sessions[token]
		if (session == null) {
			respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session."))
			return null
		}
		if (System.currentTimeMillis() - session.createdAt > 24 * 60 * 60 * 1000) {
			sessions.remove(token)
			respond(HttpStatusCode.Unauthorized, ErrorResponse("Session expired."))
			return null
		}
		return session
	}

	private suspend fun ApplicationCall.requireAdmin(): DashboardSession? {
		val session = requireAuth() ?: return null
		if (!session.isAdmin) {
			respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
			return null
		}
		return session
	}

	private fun ApplicationCall.extractToken(): String? {
		val authHeader = request.headers["Authorization"]
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			return authHeader.removePrefix("Bearer ")
		}
		return null
	}

	private fun generateToken(): String {
		val bytes = ByteArray(32)
		SecureRandom().nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	// --- Request/Response DTOs ---

	@Serializable
	data class LoginRequest(val username: String, val password: String)

	@Serializable
	data class LoginResponse(val token: String, val username: String, val isAdmin: Boolean)

	@Serializable
	data class ErrorResponse(val error: String)

	@Serializable
	data class SuccessResponse(val message: String)

	@Serializable
	data class MeResponse(val username: String, val isAdmin: Boolean)

	@Serializable
	data class AccountResponse(
		val id: String,
		val username: String,
		val registeredAt: Long,
		val linkedUUIDs: List<String>,
		val isDashboardAdmin: Boolean,
		val has2fa: Boolean = false,
	)

	@Serializable
	data class CreateAccountRequest(val username: String, val password: String)

	@Serializable
	data class RenameRequest(val newUsername: String)

	@Serializable
	data class ChangePasswordRequest(val newPassword: String)

	@Serializable
	data class SetAdminRequest(val isAdmin: Boolean)

	@Serializable
	data class InviteCodeResponse(
		val code: String,
		val createdBy: String,
		val createdAt: Long,
		val maxUses: Int,
		val currentUses: Int,
	)

	@Serializable
	data class CreateInviteRequest(val maxUses: Int = 1)

	@Serializable
	data class SessionResponse(
		val accountId: String,
		val username: String,
		val ipAddress: String,
		val expiresAt: Long,
	)

	@Serializable
	data class SoftBanResponse(
		val ipAddress: String,
		val expiresAt: Long,
	)

	@Serializable
	data class StatsResponse(
		val totalAccounts: Int,
		val activeSessions: Int,
		val activeBans: Int,
		val activeInvites: Int,
		val onlinePlayers: Int,
	)
}
