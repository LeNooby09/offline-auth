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
import java.util.*

object RegisterCommand {

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>, authManager: AuthManager) {
 	val registerNode = literal("register")
			.then(
				argument("invite_code", string())
					.then(
						argument("username", string())
							.then(
								argument("password", string())
									.executes { ctx -> execute(ctx, authManager) }
							)
					)
			)

		dispatcher.register(registerNode)
		dispatcher.register(
			literal("r").redirect(dispatcher.root.getChild("register"))
		)
	}

	private fun execute(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val inviteCode = getString(ctx, "invite_code")
		val username = getString(ctx, "username")
		val password = getString(ctx, "password")

		if (authManager.authStates[player.uuid] == AuthState.AUTHENTICATED) {
			player.sendSystemMessage(Component.literal("§cYou are already authenticated."))
			return 0
		}

		// Rate-limit and IP-based registration checks
		val rateLimitMsg = authManager.checkRegisterRateLimit(player)
		if (rateLimitMsg != null) {
			player.sendSystemMessage(rateLimitMsg)
			return 0
		}

		// Record this attempt for rate-limiting (even if invite code is wrong)
		authManager.recordRegisterAttempt(player)

		if (username.length < 3 || username.length > 16) {
			player.sendSystemMessage(Component.literal("§cUsername must be between 3 and 16 characters."))
			return 0
		}

		if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
			player.sendSystemMessage(Component.literal("§cUsername can only contain letters, numbers, and underscores."))
			return 0
		}

		if (password.length < authManager.config.minPasswordLength) {
			player.sendSystemMessage(Component.literal("§cPassword must be at least ${authManager.config.minPasswordLength} characters."))
			return 0
		}

		player.sendSystemMessage(Component.literal("§7Registering..."))

		// Offload BCrypt hashing and DB writes to the IO executor
		authManager.runAsync({
			val code = authManager.database.getInviteCode(inviteCode)
			if (code == null || code.currentUses >= code.maxUses) {
				return@runAsync "§cInvalid or expired invite code." to null
			}

			if (authManager.database.getAccountByUsername(username) != null) {
				return@runAsync "§cUsername already taken." to null
			}

			val account = AuthAccount(
				id = UUID.randomUUID(),
				username = username,
				passwordHash = BCrypt.withDefaults().hashToString(12, password.toCharArray()),
				registeredAt = System.currentTimeMillis(),
			)

			try {
				authManager.database.saveAccount(account)
				authManager.database.useInviteCode(inviteCode, username)
				authManager.recordRegistrationIp(player, account.id)
			} catch (e: Exception) {
				return@runAsync "§cRegistration failed. Please try again." to null
			}

			null to account
		}, { (errorMsg, account) ->
			if (errorMsg != null) {
				player.sendSystemMessage(Component.literal(errorMsg))
				return@runAsync
			}

			authManager.onAuthenticated(player, account!!)
			player.sendSystemMessage(Component.literal("§aRegistered and logged in as §e${account.username}§a!"))
		})

		return 1
	}
}
