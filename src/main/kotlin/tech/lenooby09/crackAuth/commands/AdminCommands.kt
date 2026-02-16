package tech.lenooby09.crackAuth.commands

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
import tech.lenooby09.crackAuth.auth.AuthManager

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
