package tech.lenooby09.offlineAuth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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
import tech.lenooby09.offlineAuth.atproto.AtprotoListResolver
import tech.lenooby09.offlineAuth.atproto.AtprotoOAuthClient
import tech.lenooby09.offlineAuth.atproto.BlueskySessionStore
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.auth.AuthMode
import tech.lenooby09.offlineAuth.commands.AdminCommands
import tech.lenooby09.offlineAuth.commands.BlueskyLoginCommand
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

		// Bluesky-mode wiring; all `null` in password mode.
		internal var blueskyHttpClient: HttpClient? = null
		internal var blueskySessionStore: BlueskySessionStore? = null
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

		// Build Bluesky-mode dependencies once if (and only if) the runtime mode is BLUESKY.
		var atprotoClient: AtprotoOAuthClient? = null
		var listResolver: AtprotoListResolver? = null
		if (manager.authMode == AuthMode.BLUESKY) {
			LOGGER.info("[Bluesky] Auth mode = BLUESKY; constructing OAuth client and list resolver.")
			try {
				val httpClient = HttpClient(CIO) {
					install(ContentNegotiation) {
						json(Json {
							ignoreUnknownKeys = true
							isLenient = true
						})
					}
				}
				blueskyHttpClient = httpClient
				atprotoClient = AtprotoOAuthClient(
					httpClient = httpClient,
					clientId = config.blueskyClientId,
					redirectUri = config.blueskyRedirectUri,
					scope = config.blueskyScope,
				)
				listResolver = AtprotoListResolver(
					httpClient = httpClient,
					rawRef = config.blueskyWhitelistList,
					cacheTtlSeconds = config.blueskyListCacheSeconds,
				)
				blueskySessionStore = BlueskySessionStore(
					pairingTokenTtlMs = config.blueskyPairingTokenTtlMinutes * 60 * 1000L,
				)
				LOGGER.info("[Bluesky] List resolver bound to {}", listResolver.atUri)
			} catch (e: Exception) {
				LOGGER.error("[Bluesky] Failed to initialize Bluesky components — running without Bluesky routes.", e)
				atprotoClient = null
				listResolver = null
				blueskySessionStore = null
				blueskyHttpClient?.close()
				blueskyHttpClient = null
			}
		}

		registerCommands(manager)
		registerEvents(manager)

		// Start the embedded web server if either the dashboard is enabled OR Bluesky mode needs it.
		val needsKtor = config.webDashboardEnabled ||
			(manager.authMode == AuthMode.BLUESKY && atprotoClient != null && listResolver != null && blueskySessionStore != null)
		if (needsKtor) {
			try {
				val dashboard = WebDashboard(
					database = database,
					authManager = manager,
					config = config,
					atprotoClient = atprotoClient,
					listResolver = listResolver,
					blueskySessionStore = blueskySessionStore,
				)
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
			blueskyHttpClient?.close()
			blueskyHttpClient = null
			blueskySessionStore = null
			manager.shutdown()
		}

		// First boot (password mode only): generate a one-time admin invite code if no accounts exist.
		// In Bluesky mode the invite-code system is disabled at runtime, so don't create a stale code.
		if (manager.authMode == AuthMode.PASSWORD &&
			!database.hasAnyAccounts() &&
			database.getActiveInviteCodes().isEmpty()
		) {
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
			// Legacy password commands stay always-registered; their executors
			// short-circuit via AuthManager.denyIfBluesky when authMode=BLUESKY.
			RegisterCommand.register(dispatcher, manager)
			LoginCommand.register(dispatcher, manager)
			ChangePasswordCommand.register(dispatcher, manager)
			AdminCommands.register(dispatcher, manager)
			TwoFactorCommand.register(dispatcher, manager)

			// /bluesky is registered only when the mode is BLUESKY and the session store exists.
			if (manager.authMode == AuthMode.BLUESKY) {
				val store = blueskySessionStore
				if (store != null) {
					BlueskyLoginCommand.register(dispatcher, manager, store)
				}
			}
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
			// Void any pending Bluesky pairing tokens for this player so they can't be reused.
			blueskySessionStore?.voidByPlayer(handler.player.uuid)
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
