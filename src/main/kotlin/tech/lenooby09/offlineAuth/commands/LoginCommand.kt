package tech.lenooby09.offlineAuth.commands

import at.favre.lib.crypto.bcrypt.BCrypt
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import tech.lenooby09.offlineAuth.auth.AuthAccount
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.auth.AuthState

object LoginCommand {

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>, authManager: AuthManager) {
		// /login <password> — for players who already linked their MC account
		dispatcher.register(
			literal("login")
				.then(
					argument("password", string())
						.executes { ctx -> executeSimple(ctx, authManager) }
				)
		)
		dispatcher.register(
			literal("l").redirect(dispatcher.root.getChild("login"))
		)

		// /login_as <username> <password> — for logging in from a new MC account
		dispatcher.register(
			literal("login_as")
				.then(
					argument("username", string())
						.then(
							argument("password", string())
								.executes { ctx -> executeWithUsername(ctx, authManager) }
						)
				)
		)
		dispatcher.register(
			literal("ls").redirect(dispatcher.root.getChild("login_as"))
		)
	}

	private fun executeSimple(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val password = getString(ctx, "password")
		val alreadyAuthenticated = authManager.authStates[player.uuid] == AuthState.AUTHENTICATED

		val account = authManager.database.getAccountByMinecraftUUID(player.uuid)
		if (account == null) {
			player.sendSystemMessage(
				Component.literal("§cNo account linked to this client. Use §e/login_as <username> <password> §cor §e/register §cfirst.")
			)
			return 0
		}

		// Check per-account lockout before verifying password
		val lockoutMsg = checkAccountLockout(account, authManager)
		if (lockoutMsg != null) {
			player.sendSystemMessage(lockoutMsg)
			return 0
		}

		val result = BCrypt.verifyer().verify(password.toCharArray(), account.passwordHash)
		if (!result.verified) {
			return handleFailedLogin(player, authManager, account)
		}

		// Successful login — reset per-account lockout
		authManager.database.resetLoginAttempts(account.id)

		if (alreadyAuthenticated) {
			authManager.prepareAccountSwitch(player)
		}
		authManager.onAuthenticated(player, account)
		player.sendSystemMessage(Component.literal("§aLogged in as §e${account.username}§a!"))
		return 1
	}

	private fun executeWithUsername(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val username = getString(ctx, "username")
		val password = getString(ctx, "password")
		val alreadyAuthenticated = authManager.authStates[player.uuid] == AuthState.AUTHENTICATED

		val account = authManager.database.getAccountByUsername(username)
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cAccount not found."))
			return 0
		}

		// Check per-account lockout before verifying password
		val lockoutMsg = checkAccountLockout(account, authManager)
		if (lockoutMsg != null) {
			player.sendSystemMessage(lockoutMsg)
			return 0
		}

		val result = BCrypt.verifyer().verify(password.toCharArray(), account.passwordHash)
		if (!result.verified) {
			return handleFailedLogin(player, authManager, account)
		}

		// Successful login — reset per-account lockout
		authManager.database.resetLoginAttempts(account.id)

		if (alreadyAuthenticated) {
			authManager.prepareAccountSwitch(player)
		}
		authManager.onAuthenticated(player, account)
		player.sendSystemMessage(Component.literal("§aLogged in as §e${account.username}§a!"))
		return 1
	}

	/**
	 * Checks if an account is currently locked out due to too many failed login attempts.
	 * Returns an error Component if locked, or null if login may proceed.
	 */
	private fun checkAccountLockout(account: AuthAccount, authManager: AuthManager): Component? {
		val record = authManager.database.getLoginAttempts(account.id) ?: return null
		val now = System.currentTimeMillis()
		if (record.lockedUntil > now) {
			val remaining = (record.lockedUntil - now) / 1000
			return Component.literal("§cAccount is locked due to too many failed attempts. Try again in ${remaining}s.")
		}
		return null
	}

	/**
	 * Handles a failed login attempt with per-account persistent tracking and exponential backoff.
	 * After [config.maxLoginAttempts] failures, the account is locked for an exponentially increasing duration.
	 */
	private fun handleFailedLogin(
		player: net.minecraft.server.level.ServerPlayer,
		authManager: AuthManager,
		account: AuthAccount? = null
	): Int {
		// Per-session tracking (kick after max attempts in a single session)
		authManager.loginAttempts.merge(player.uuid, 1, Int::plus)
		val sessionAttempts = authManager.loginAttempts[player.uuid] ?: 0

		// Per-account persistent tracking with exponential backoff
		if (account != null) {
			val record = authManager.database.getLoginAttempts(account.id)
			val currentFailed = (record?.failedCount ?: 0) + 1
			val config = authManager.config

			var lockedUntil = 0L
			if (currentFailed >= config.maxLoginAttempts) {
				// Exponential backoff: base * 2^(lockout_cycles)
				// lockout_cycles = how many times we've hit the max (currentFailed / maxLoginAttempts - 1)
				val lockoutCycles = (currentFailed / config.maxLoginAttempts) - 1
				val backoffSeconds = (config.loginLockoutBaseSeconds * (1L shl lockoutCycles.coerceAtMost(20)))
					.coerceAtMost(config.loginLockoutMaxSeconds)
				lockedUntil = System.currentTimeMillis() + (backoffSeconds * 1000)
			}
			authManager.database.recordFailedLogin(account.id, lockedUntil)

			if (lockedUntil > 0) {
				val lockDuration = (lockedUntil - System.currentTimeMillis()) / 1000
				player.sendSystemMessage(
					Component.literal("§cToo many failed attempts. Account locked for ${lockDuration}s.")
				)
				return 0
			}
		}

		if (sessionAttempts >= authManager.config.maxLoginAttempts) {
			player.connection.disconnect(Component.literal("§cToo many failed login attempts."))
			return 0
		}
		player.sendSystemMessage(
			Component.literal("§cWrong password. (${sessionAttempts}/${authManager.config.maxLoginAttempts} attempts)")
		)
		return 0
	}
}
