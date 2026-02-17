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
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.auth.AuthState

object ChangePasswordCommand {

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>, authManager: AuthManager) {
		dispatcher.register(
			literal("changepassword")
				.then(
					argument("old_password", string())
						.then(
							argument("new_password", string())
								.executes { ctx -> execute(ctx, authManager) }
						)
				)
		)
	}

	private fun execute(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val oldPassword = getString(ctx, "old_password")
		val newPassword = getString(ctx, "new_password")

		if (authManager.authStates[player.uuid] != AuthState.AUTHENTICATED) {
			player.sendSystemMessage(Component.literal("§cYou must be logged in to change your password."))
			return 0
		}

		val account = authManager.accountMap[player.uuid]
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cNo account found for your session."))
			return 0
		}

		if (newPassword.length < authManager.config.minPasswordLength) {
			player.sendSystemMessage(Component.literal("§cNew password must be at least ${authManager.config.minPasswordLength} characters."))
			return 0
		}

		player.sendSystemMessage(Component.literal("§7Changing password..."))

		// Offload BCrypt verify + hash + DB write to the IO executor
		authManager.runAsync({
			val result = BCrypt.verifyer().verify(oldPassword.toCharArray(), account.passwordHash)
			if (!result.verified) {
				return@runAsync null to "§cIncorrect current password."
			}

			val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())

			try {
				authManager.database.updatePasswordHash(account.id, newHash)
				newHash to null
			} catch (e: Exception) {
				null to "§cFailed to change password. Please try again."
			}
		}, { (newHash, errorMsg) ->
			if (errorMsg != null) {
				player.sendSystemMessage(Component.literal(errorMsg))
				return@runAsync
			}

			// Update the in-memory account with the new hash
			authManager.accountMap[player.uuid] = account.copy(passwordHash = newHash!!)
			player.sendSystemMessage(Component.literal("§aPassword changed successfully."))
		})

		return 1
	}
}
