package tech.lenooby09.crackAuth.commands

import at.favre.lib.crypto.bcrypt.BCrypt
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import tech.lenooby09.crackAuth.auth.AuthManager
import tech.lenooby09.crackAuth.auth.AuthState

object LoginCommand {

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>, authManager: AuthManager) {
		// /login <password> — for players who already linked their MC account
		dispatcher.register(
			literal("login")
				.then(
					argument("password", string())
						.executes { ctx -> executeSimple(ctx, authManager) }
						.then(
							// This branch won't trigger because string() is greedy...
							// So we use a separate command tree below
							argument("_dummy", string()).executes { ctx -> executeSimple(ctx, authManager) }
						)
				)
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
	}

	private fun executeSimple(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val password = getString(ctx, "password")

		if (authManager.authStates[player.uuid] == AuthState.AUTHENTICATED) {
			player.sendSystemMessage(Component.literal("§cAlready authenticated."))
			return 0
		}

		val account = authManager.database.getAccountByMinecraftUUID(player.uuid)
		if (account == null) {
			player.sendSystemMessage(
				Component.literal("§cNo account linked to this client. Use §e/login_as <username> <password> §cor §e/register §cfirst.")
			)
			return 0
		}

		val result = BCrypt.verifyer().verify(password.toCharArray(), account.passwordHash)
		if (!result.verified) {
			return handleFailedLogin(player, authManager)
		}

		authManager.onAuthenticated(player, account)
		player.sendSystemMessage(Component.literal("§aLogged in as §e${account.username}§a!"))
		return 1
	}

	private fun executeWithUsername(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val username = getString(ctx, "username")
		val password = getString(ctx, "password")

		if (authManager.authStates[player.uuid] == AuthState.AUTHENTICATED) {
			player.sendSystemMessage(Component.literal("§cAlready authenticated."))
			return 0
		}

		val account = authManager.database.getAccountByUsername(username)
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cAccount not found."))
			return 0
		}

		val result = BCrypt.verifyer().verify(password.toCharArray(), account.passwordHash)
		if (!result.verified) {
			return handleFailedLogin(player, authManager)
		}

		authManager.onAuthenticated(player, account)
		player.sendSystemMessage(Component.literal("§aLogged in as §e${account.username}§a!"))
		return 1
	}

	private fun handleFailedLogin(
		player: net.minecraft.server.level.ServerPlayer,
		authManager: AuthManager
	): Int {
		authManager.loginAttempts.merge(player.uuid, 1, Int::plus)
		val attempts = authManager.loginAttempts[player.uuid] ?: 0
		if (attempts >= authManager.config.maxLoginAttempts) {
			player.connection.disconnect(Component.literal("§cToo many failed login attempts."))
			return 0
		}
		player.sendSystemMessage(
			Component.literal("§cWrong password. (${attempts}/${authManager.config.maxLoginAttempts} attempts)")
		)
		return 0
	}
}
