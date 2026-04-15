package tech.lenooby09.offlineAuth.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import tech.lenooby09.offlineAuth.OfflineAuth
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.auth.AuthState
import tech.lenooby09.offlineAuth.auth.QrCodeUtil
import tech.lenooby09.offlineAuth.auth.TotpUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TwoFactorCommand {

	// Temporary storage of pending 2FA setup secrets (player UUID -> secret) until confirmed
	private val pendingSetup = ConcurrentHashMap<UUID, String>()

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>, authManager: AuthManager) {
		dispatcher.register(
			literal("2fa")
				.then(
					literal("setup")
						.executes { ctx -> executeSetup(ctx, authManager) }
				)
				.then(
					literal("confirm")
						.then(
							argument("code", string())
								.executes { ctx -> executeConfirm(ctx, authManager) }
						)
				)
				.then(
					literal("disable")
						.then(
							argument("code", string())
								.executes { ctx -> executeDisable(ctx, authManager) }
						)
				)
				.then(
					literal("verify")
						.then(
							argument("code", string())
								.executes { ctx -> executeVerify(ctx, authManager) }
						)
				)
				.then(
					literal("status")
						.executes { ctx -> executeStatus(ctx, authManager) }
				)
		)
	}

	private fun executeSetup(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException

		if (!authManager.isAuthenticated(player)) {
			player.sendSystemMessage(Component.literal("§cYou must be logged in to set up 2FA."))
			return 0
		}

		val account = authManager.accountMap[player.uuid]
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cNo account found."))
			return 0
		}

		authManager.runAsync({
			authManager.database.has2faEnabled(account.id)
		}, { alreadyEnabled ->
			if (alreadyEnabled) {
				player.sendSystemMessage(Component.literal("§c2FA is already enabled on your account. Disable it first with §6/2fa disable <code>"))
				return@runAsync
			}

			val secret = TotpUtil.generateSecret()
			pendingSetup[player.uuid] = secret

			val uri = TotpUtil.generateOtpAuthUri(secret, account.username)
			player.sendSystemMessage(Component.literal("§6§l═══ 2FA Setup ═══"))

			// Offer a clickable link to view the QR code in browser (if web dashboard is enabled and domain is configured)
			val cfg = OfflineAuth.config
			if (cfg.webDashboardEnabled && cfg.webDashboardDomain.isNotEmpty()) {
				val qrToken = QrCodeUtil.storeQrImage(uri)
				if (qrToken != null) {
					val qrUrl = "https://${cfg.webDashboardDomain}/qr/$qrToken"
					val qrComponent = Component.literal("§b§n[Click here to view QR code]").withStyle(
						Style.EMPTY
							.withClickEvent(ClickEvent.OpenUrl(java.net.URI(qrUrl)))
							.withHoverEvent(HoverEvent.ShowText(Component.literal("§eOpen QR code in your browser")))
					)
					player.sendSystemMessage(qrComponent)
				}
			}

			player.sendSystemMessage(Component.literal("§eYour secret key:"))

			val secretComponent = Component.literal("§f$secret").withStyle(
				Style.EMPTY
					.withClickEvent(ClickEvent.CopyToClipboard(secret))
					.withHoverEvent(HoverEvent.ShowText(Component.literal("§eClick to copy Secret to clipboard")))
			)
			player.sendSystemMessage(secretComponent)

			player.sendSystemMessage(Component.literal("§7Add this key to your authenticator app (Google Authenticator, Authy, etc.)"))
			player.sendSystemMessage(Component.literal("§7Or use this URI:"))

			val uriComponent = Component.literal("§f$uri").withStyle(
				Style.EMPTY
					.withClickEvent(ClickEvent.CopyToClipboard(uri))
					.withHoverEvent(HoverEvent.ShowText(Component.literal("§eClick to copy URI to clipboard")))
			)
			player.sendSystemMessage(uriComponent)

			player.sendSystemMessage(Component.literal("§eThen confirm with: §6/2fa confirm <code>"))
			player.sendSystemMessage(Component.literal("§6§l═════════════"))
		})

		return 1
	}

	private fun executeConfirm(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val code = getString(ctx, "code")

		if (!authManager.isAuthenticated(player)) {
			player.sendSystemMessage(Component.literal("§cYou must be logged in to confirm 2FA setup."))
			return 0
		}

		val account = authManager.accountMap[player.uuid]
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cNo account found."))
			return 0
		}

		val secret = pendingSetup[player.uuid]
		if (secret == null) {
			player.sendSystemMessage(Component.literal("§cNo pending 2FA setup. Run §6/2fa setup §cfirst."))
			return 0
		}

		if (!TotpUtil.verifyCode(secret, code)) {
			player.sendSystemMessage(Component.literal("§cInvalid code. Please try again with a fresh code from your authenticator app."))
			return 0
		}

		authManager.runAsyncFire {
			authManager.database.setTotpSecret(account.id, secret)
		}

		pendingSetup.remove(player.uuid)
		player.sendSystemMessage(Component.literal("§a2FA has been enabled on your account!"))
		player.sendSystemMessage(Component.literal("§7You will need to enter a 2FA code after your password on each login."))

		return 1
	}

	private fun executeDisable(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val code = getString(ctx, "code")

		if (!authManager.isAuthenticated(player)) {
			player.sendSystemMessage(Component.literal("§cYou must be logged in to disable 2FA."))
			return 0
		}

		val account = authManager.accountMap[player.uuid]
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cNo account found."))
			return 0
		}

		authManager.runAsync({
			authManager.database.getTotpSecret(account.id)
		}, { secret ->
			if (secret == null) {
				player.sendSystemMessage(Component.literal("§c2FA is not enabled on your account."))
				return@runAsync
			}

			if (!TotpUtil.verifyCode(secret, code)) {
				player.sendSystemMessage(Component.literal("§cInvalid 2FA code. Cannot disable 2FA."))
				return@runAsync
			}

			authManager.runAsyncFire {
				authManager.database.setTotpSecret(account.id, null)
			}

			player.sendSystemMessage(Component.literal("§a✓ 2FA has been disabled on your account."))
		})

		return 1
	}

	private fun executeVerify(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException
		val code = getString(ctx, "code")

		if (authManager.authStates[player.uuid] != AuthState.AWAITING_2FA) {
			player.sendSystemMessage(Component.literal("§cYou are not awaiting 2FA verification."))
			return 0
		}

		val account = authManager.pending2fa[player.uuid]
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cNo pending 2FA session. Please log in again."))
			authManager.authStates[player.uuid] = AuthState.UNAUTHENTICATED
			return 0
		}

		authManager.runAsync({
			val secret = authManager.database.getTotpSecret(account.id)
			if (secret != null && TotpUtil.verifyCode(secret, code)) secret else null
		}, { secret ->
			if (secret == null) {
				player.sendSystemMessage(Component.literal("§cInvalid 2FA code. Please try again."))
				return@runAsync
			}

			authManager.pending2fa.remove(player.uuid)
			authManager.onAuthenticated(player, account)
			player.sendSystemMessage(Component.literal("§aLogged in as §e${account.username}§a!"))
		})

		return 1
	}

	private fun executeStatus(ctx: CommandContext<CommandSourceStack>, authManager: AuthManager): Int {
		val player = ctx.source.playerOrException

		if (!authManager.isAuthenticated(player)) {
			player.sendSystemMessage(Component.literal("§cYou must be logged in to check 2FA status."))
			return 0
		}

		val account = authManager.accountMap[player.uuid]
		if (account == null) {
			player.sendSystemMessage(Component.literal("§cNo account found."))
			return 0
		}

		authManager.runAsync({
			authManager.database.has2faEnabled(account.id)
		}, { enabled ->
			if (enabled) {
				player.sendSystemMessage(Component.literal("§a2FA is §lenabled §aon your account."))
			} else {
				player.sendSystemMessage(Component.literal("§e2FA is §lnot enabled §eon your account. Use §6/2fa setup §eto enable it."))
			}
		})

		return 1
	}
}
