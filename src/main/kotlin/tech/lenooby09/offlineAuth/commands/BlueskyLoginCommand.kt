package tech.lenooby09.offlineAuth.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import tech.lenooby09.offlineAuth.OfflineAuth
import tech.lenooby09.offlineAuth.atproto.BlueskySessionStore
import tech.lenooby09.offlineAuth.atproto.PendingPairing
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.auth.AuthState
import tech.lenooby09.offlineAuth.auth.QrCodeUtil
import java.security.SecureRandom
import java.util.Base64

/**
 * In-game `/bluesky` (alias `/b`) command. Registered only when
 * `authManager.authMode == BLUESKY`. Generates a one-time pairing token bound
 * to the player's UUID and offers a clickable chat link plus a QR-code link
 * pointing at the embedded web server's `/bluesky/login/<token>` page.
 */
object BlueskyLoginCommand {

	private val secureRandom = SecureRandom()

	fun register(
		dispatcher: CommandDispatcher<CommandSourceStack>,
		authManager: AuthManager,
		sessionStore: BlueskySessionStore,
	) {
		dispatcher.register(
			literal("bluesky")
				.executes { ctx -> execute(ctx, authManager, sessionStore) }
		)
		dispatcher.register(
			literal("b").redirect(dispatcher.root.getChild("bluesky"))
		)
	}

	private fun execute(
		ctx: CommandContext<CommandSourceStack>,
		authManager: AuthManager,
		sessionStore: BlueskySessionStore,
	): Int {
		val player = ctx.source.playerOrException

		if (authManager.authStates[player.uuid] == AuthState.AUTHENTICATED) {
			player.sendSystemMessage(Component.literal("§cYou are already authenticated."))
			return 0
		}

		val cfg = OfflineAuth.config
		if (cfg.blueskyPublicUrl.isBlank()) {
			player.sendSystemMessage(
				Component.literal("§cBluesky auth is misconfigured: bluesky-public-url is empty. Ask the operator.")
			)
			return 0
		}

		val pairingToken = generatePairingToken()
		sessionStore.storePairing(pairingToken, PendingPairing(playerUuid = player.uuid))

		val baseUrl = cfg.blueskyPublicUrl.trimEnd('/')
		val loginUrl = "$baseUrl/bluesky/login/$pairingToken"

		player.sendSystemMessage(Component.empty())
		player.sendSystemMessage(Component.literal("§b§l═══ Bluesky Login ═══"))
		player.sendSystemMessage(Component.literal("§7Click the link below to authenticate via Bluesky:"))

		val linkComponent = Component.literal("§b§n[Sign in with Bluesky]").withStyle(
			Style.EMPTY
				.withClickEvent(ClickEvent.OpenUrl(java.net.URI(loginUrl)))
				.withHoverEvent(HoverEvent.ShowText(Component.literal("§eOpen $loginUrl")))
		)
		player.sendSystemMessage(linkComponent)

		// Best-effort QR code link.
		val qrToken = try {
			QrCodeUtil.storeQrImage(loginUrl)
		} catch (e: Exception) {
			OfflineAuth.LOGGER.warn("Failed to generate QR for /bluesky", e)
			null
		}
		if (qrToken != null) {
			val qrBase = if (cfg.webDashboardDomain.isNotEmpty()) {
				"https://${cfg.webDashboardDomain}"
			} else {
				baseUrl
			}
			val qrUrl = "$qrBase/qr/$qrToken"
			val qrComponent = Component.literal("§b§n[Show QR code]").withStyle(
				Style.EMPTY
					.withClickEvent(ClickEvent.OpenUrl(java.net.URI(qrUrl)))
					.withHoverEvent(HoverEvent.ShowText(Component.literal("§eOpen the QR code in your browser")))
			)
			player.sendSystemMessage(qrComponent)
		}

		player.sendSystemMessage(
			Component.literal("§7This link expires in §c${cfg.blueskyPairingTokenTtlMinutes} minutes§7.")
		)
		player.sendSystemMessage(Component.literal("§b§l═════════════════"))

		return 1
	}

	private fun generatePairingToken(): String {
		val bytes = ByteArray(24)
		secureRandom.nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}
}
