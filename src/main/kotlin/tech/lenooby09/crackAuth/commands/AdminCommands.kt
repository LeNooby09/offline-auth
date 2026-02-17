package tech.lenooby09.crackAuth.commands

import at.favre.lib.crypto.bcrypt.BCrypt
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import tech.lenooby09.crackAuth.auth.AuthAccount
import tech.lenooby09.crackAuth.auth.AuthManager
import java.util.*

object AdminCommands {

	private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

	fun generateInviteCode(length: Int): String {
		val chars = buildString {
			repeat(length) { append(CODE_CHARS.random()) }
		}
		// Insert dashes every 4 characters for readability
		return chars.chunked(4).joinToString("-")
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>, authManager: AuthManager) {
		dispatcher.register(
			literal("crackauth")
				.requires { it.permissions().hasPermission(Permissions.COMMANDS_OWNER) }
				.then(
					literal("generate")
						.executes { ctx -> generateCode(ctx, authManager, 1) }
						.then(
							argument("max_uses", IntegerArgumentType.integer(1))
								.executes { ctx ->
									val maxUses = IntegerArgumentType.getInteger(ctx, "max_uses")
									generateCode(ctx, authManager, maxUses)
								}
						)
				)
				.then(
					literal("list")
						.executes { ctx -> listCodes(ctx, authManager) }
				)
				.then(
					literal("revoke")
						.then(
							argument("code", string())
								.executes { ctx -> revokeCode(ctx, authManager) }
						)
				)
				.then(
					literal("createuser")
						.then(
							argument("username", string())
								.then(
									argument("password", string())
										.executes { ctx -> createUser(ctx, authManager) }
								)
						)
				)
				.then(
					literal("deleteuser")
						.then(
							argument("username", string())
								.executes { ctx -> deleteUser(ctx, authManager) }
						)
				)
		)
	}

	private fun generateCode(
		ctx: CommandContext<CommandSourceStack>,
		authManager: AuthManager,
		maxUses: Int
	): Int {
		val code = generateInviteCode(authManager.config.inviteCodeLength)

		val createdBy = ctx.source.textName
		authManager.database.saveInviteCode(code, createdBy, System.currentTimeMillis(), maxUses)

		ctx.source.sendSuccess(
			{ Component.literal("§aGenerated invite code: §e$code §a(max uses: $maxUses)") },
			false
		)
		return 1
	}

	private fun listCodes(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val codes = authManager.database.getActiveInviteCodes()

		if (codes.isEmpty()) {
			ctx.source.sendSuccess(
				{ Component.literal("§7No active invite codes.") },
				false
			)
			return 1
		}

		ctx.source.sendSuccess(
			{ Component.literal("§e§l--- Active Invite Codes ---") },
			false
		)
		for (code in codes) {
			ctx.source.sendSuccess(
				{
					Component.literal(
						"§e${code.code} §7| Uses: §f${code.currentUses}/${code.maxUses} §7| By: §f${code.createdBy}"
					)
				},
				false
			)
		}
		return 1
	}

	private fun createUser(
		ctx: CommandContext<CommandSourceStack>,
		authManager: AuthManager
	): Int {
		val username = getString(ctx, "username")
		val password = getString(ctx, "password")

		if (username.length < 3 || username.length > 16) {
			ctx.source.sendFailure(Component.literal("§cUsername must be between 3 and 16 characters."))
			return 0
		}

		if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
			ctx.source.sendFailure(Component.literal("§cUsername can only contain letters, numbers, and underscores."))
			return 0
		}

		if (password.length < authManager.config.minPasswordLength) {
			ctx.source.sendFailure(Component.literal("§cPassword must be at least ${authManager.config.minPasswordLength} characters."))
			return 0
		}

		if (authManager.database.getAccountByUsername(username) != null) {
			ctx.source.sendFailure(Component.literal("§cUsername already taken."))
			return 0
		}

		val account = AuthAccount(
			id = UUID.randomUUID(),
			username = username,
			passwordHash = BCrypt.withDefaults().hashToString(12, password.toCharArray()),
			registeredAt = System.currentTimeMillis(),
		)

		try {
			authManager.database.saveAccount(account)
		} catch (e: Exception) {
			ctx.source.sendFailure(Component.literal("§cFailed to create user. Please try again."))
			return 0
		}

		ctx.source.sendSuccess(
			{ Component.literal("§aCreated user: §e$username") },
			false
		)
		return 1
	}

	private fun deleteUser(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val username = getString(ctx, "username")

		val success = authManager.database.deleteAccountByUsername(username)

		if (success) {
			ctx.source.sendSuccess(
				{ Component.literal("§aDeleted user: §e$username") },
				false
			)
		} else {
			ctx.source.sendFailure(Component.literal("§cUser not found: $username"))
		}
		return if (success) 1 else 0
	}

	private fun revokeCode(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val code = getString(ctx, "code")
		val success = authManager.database.revokeInviteCode(code)

		if (success) {
			ctx.source.sendSuccess(
				{ Component.literal("§aRevoked invite code: §e$code") },
				false
			)
		} else {
			ctx.source.sendFailure(Component.literal("§cInvite code not found: $code"))
		}
		return if (success) 1 else 0
	}
}
