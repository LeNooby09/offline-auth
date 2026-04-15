package tech.lenooby09.offlineAuth

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.*
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.slf4j.LoggerFactory
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.commands.AdminCommands
import tech.lenooby09.offlineAuth.commands.ChangePasswordCommand
import tech.lenooby09.offlineAuth.commands.LoginCommand
import tech.lenooby09.offlineAuth.commands.RegisterCommand
import tech.lenooby09.offlineAuth.commands.TwoFactorCommand
import tech.lenooby09.offlineAuth.config.OfflineAuthConfig
import tech.lenooby09.offlineAuth.storage.DatabaseManager
import tech.lenooby09.offlineAuth.web.WebDashboard

class OfflineAuth : ModInitializer {

	companion object {
		const val MOD_ID = "offline-auth"
		val LOGGER = LoggerFactory.getLogger(MOD_ID)

		var authManager: AuthManager? = null
			private set

		var config: OfflineAuthConfig = OfflineAuthConfig()
			internal set

		var configDir: java.nio.file.Path? = null
			private set

		var webDashboard: WebDashboard? = null
			private set
	}

	override fun onInitialize() {
		LOGGER.info("OfflineAuth initializing...")

		val cfgDir = FabricLoader.getInstance().configDir.resolve(MOD_ID)
		cfgDir.toFile().mkdirs()
		configDir = cfgDir

		config = OfflineAuthConfig.load(cfgDir)
		LOGGER.info("Config loaded: authTimeout=${config.authTimeoutSeconds}s, softBan=${config.softBanMinutes}min, maxAttempts=${config.maxLoginAttempts}, minPwLen=${config.minPasswordLength}, skyY=${config.skyY}, autoAuthOps=${config.autoAuthOps}, inviteCodeLength=${config.inviteCodeLength}, databaseType=${config.databaseType}")

		val database = if (config.databaseType.equals("postgresql", ignoreCase = true)) {
			LOGGER.info("Using PostgreSQL database at ${config.postgresHost}:${config.postgresPort}/${config.postgresDatabase}")
			DatabaseManager(config)
		} else {
			val dbPath = cfgDir.resolve("offlineauth.db")
			LOGGER.info("Using SQLite database at $dbPath")
			DatabaseManager(dbPath)
		}
		val manager = AuthManager(database, config)
		authManager = manager

		registerCommands(manager)
		registerEvents(manager)

		// Start web dashboard if enabled
		if (config.webDashboardEnabled) {
			try {
				val dashboard = WebDashboard(database, manager, config)
				dashboard.start()
				webDashboard = dashboard
			} catch (e: Exception) {
				LOGGER.error("Failed to start web dashboard", e)
			}
		}

		ServerLifecycleEvents.SERVER_STOPPING.register {
			LOGGER.info("OfflineAuth shutting down...")
			webDashboard?.stop()
			webDashboard = null
			manager.shutdown()
		}

		// First boot: generate a one-time admin invite code if no accounts exist
		if (!database.hasAnyAccounts() && database.getActiveInviteCodes().isEmpty()) {
 			val adminCode = AdminCommands.generateInviteCode(config.inviteCodeLength)
			database.saveInviteCode(adminCode, "SYSTEM", System.currentTimeMillis(), 1)
			LOGGER.info("  No accounts found. A one-time admin invite code has been generated:")
			LOGGER.info("  One time register code: $adminCode")
			LOGGER.info("  Use: /register $adminCode <username> <password>")
		}

		LOGGER.info("OfflineAuth initialized successfully!")
	}

	private fun registerCommands(manager: AuthManager) {
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			RegisterCommand.register(dispatcher, manager)
			LoginCommand.register(dispatcher, manager)
			ChangePasswordCommand.register(dispatcher, manager)
			AdminCommands.register(dispatcher, manager)
			TwoFactorCommand.register(dispatcher, manager)
		}
	}

	private fun registerEvents(manager: AuthManager) {
		// Player join — start auth timer
		ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
			manager.onPlayerJoin(handler.player, server)
		}

		// Player disconnect — cleanup
		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			manager.onPlayerDisconnect(handler.player)
		}

		// Block chat messages from unauthenticated players
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { _, player, _ ->
			if (!manager.isAuthenticated(player)) {
				player.sendSystemMessage(
					net.minecraft.network.chat.Component.literal("§cYou must authenticate first.")
				)
				false
			} else {
				true
			}
		}

		// Block block-breaking
		AttackBlockCallback.EVENT.register { player, _, _, _, _ ->
			if (player is ServerPlayer && !manager.isAuthenticated(player)) {
				InteractionResult.FAIL
			} else {
				InteractionResult.PASS
			}
		}

		// Block block-use
		UseBlockCallback.EVENT.register { player, _, _, _ ->
			if (player is ServerPlayer && !manager.isAuthenticated(player)) {
				InteractionResult.FAIL
			} else {
				InteractionResult.PASS
			}
		}

		// Block item use
		UseItemCallback.EVENT.register { player, _, _ ->
			if (player is ServerPlayer && !manager.isAuthenticated(player)) {
				InteractionResult.FAIL
			} else {
				InteractionResult.PASS
			}
		}

		// Block entity attack
		AttackEntityCallback.EVENT.register { player, _, _, _, _ ->
			if (player is ServerPlayer && !manager.isAuthenticated(player)) {
				InteractionResult.FAIL
			} else {
				InteractionResult.PASS
			}
		}

		// Block entity interaction
		UseEntityCallback.EVENT.register { player, _, _, _, _ ->
			if (player is ServerPlayer && !manager.isAuthenticated(player)) {
				InteractionResult.FAIL
			} else {
				InteractionResult.PASS
			}
		}
	}
}
