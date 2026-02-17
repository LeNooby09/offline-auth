package tech.lenooby09.offlineAuth.auth

import com.mojang.authlib.GameProfile
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.util.ProblemReporter
import net.minecraft.world.ItemStackWithSlot
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import tech.lenooby09.offlineAuth.OfflineAuth
import tech.lenooby09.offlineAuth.config.OfflineAuthConfig
import tech.lenooby09.offlineAuth.mixin.GameProfileAccessor
import tech.lenooby09.offlineAuth.storage.DatabaseManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AuthManager(val database: DatabaseManager, var config: OfflineAuthConfig) {

	val authStates = ConcurrentHashMap<UUID, AuthState>()
	val accountMap = ConcurrentHashMap<UUID, AuthAccount>()
	val loginAttempts = ConcurrentHashMap<UUID, Int>()

	private val kickTimers = ConcurrentHashMap<UUID, ScheduledFuture<*>>()
	private val warningTimers = ConcurrentHashMap<UUID, MutableList<ScheduledFuture<*>>>()
	private val scheduler = Executors.newScheduledThreadPool(1)

	// Registration rate-limiting: IP -> (attempt count, first attempt timestamp)
	private val registerAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()
	// Registration IP cooldowns: IP -> cooldown expiry timestamp
	private val registerCooldowns = ConcurrentHashMap<String, Long>()

	// Reverse mapping: account ID -> currently logged-in player UUID
	private val activeAccountSessions = ConcurrentHashMap<UUID, UUID>()

	private var server: MinecraftServer? = null

	// IO executor for offloading BCrypt hashing and database operations off the main thread
	val ioExecutor: ExecutorService = Executors.newFixedThreadPool(4)

	data class SpawnPos(val x: Double, val y: Double, val z: Double, val dimension: String)
	private val spawnPositions = ConcurrentHashMap<UUID, SpawnPos>()

	init {
		// Clean up expired soft-bans on startup
		database.cleanExpiredSoftBans()
		// Periodically clean expired soft-bans and sessions
		scheduler.scheduleAtFixedRate({
			try {
				database.cleanExpiredSoftBans()
				database.cleanExpiredSessions()
			} catch (e: Exception) {
				OfflineAuth.LOGGER.error("Failed to clean expired data", e)
			}
		}, 1, 1, TimeUnit.HOURS)
	}


	fun isAuthenticated(player: ServerPlayer): Boolean {
		return authStates[player.uuid] == AuthState.AUTHENTICATED
	}

	fun isAuthenticated(uuid: UUID): Boolean {
		return authStates[uuid] == AuthState.AUTHENTICATED
	}

	fun onPlayerJoin(player: ServerPlayer, server: MinecraftServer) {
		this.server = server

		// Auto-authenticate OP players
		if (config.autoAuthOps && server.playerList.isOp(NameAndId(player.gameProfile))) {
			authStates[player.uuid] = AuthState.AUTHENTICATED

			val linkedAccount = database.getAccountByMinecraftUUID(player.uuid)
			if (linkedAccount != null) {
				// Kick any existing session for this account
				kickExistingSession(account = linkedAccount, newPlayerUuid = player.uuid)
				accountMap[player.uuid] = linkedAccount
				activeAccountSessions[linkedAccount.id] = player.uuid
				player.customName = Component.literal(linkedAccount.username)
				player.isCustomNameVisible = true
				updateGameProfileName(player, linkedAccount.username)
			}

			player.sendSystemMessage(Component.empty())
			player.sendSystemMessage(Component.literal("§aAuto-authenticated as server operator."))
			player.sendSystemMessage(Component.empty())
			return
		}

		authStates[player.uuid] = AuthState.UNAUTHENTICATED

		// Check for session persistence — auto-authenticate if valid session exists
		val linkedAccount = database.getAccountByMinecraftUUID(player.uuid)
		if (config.sessionPersistenceEnabled && linkedAccount != null) {
			val ip = extractAddress(player)
			OfflineAuth.LOGGER.info("Session persistence check for ${player.gameProfile.name}: ip=$ip, account=${linkedAccount.username}")
			if (ip != null && database.getValidSession(linkedAccount.id, ip)) {
				// Valid session found — auto-authenticate
				authStates[player.uuid] = AuthState.AUTHENTICATED
				kickExistingSession(account = linkedAccount, newPlayerUuid = player.uuid)
				accountMap[player.uuid] = linkedAccount
				activeAccountSessions[linkedAccount.id] = player.uuid
				database.linkMinecraftAccount(player.uuid, linkedAccount.id)
				player.customName = Component.literal(linkedAccount.username)
				player.isCustomNameVisible = true
				updateGameProfileName(player, linkedAccount.username)
				loadPlayerInventory(player, linkedAccount)
				val accountPos = database.loadAccountPosition(linkedAccount.id)
				if (accountPos != null) {
					val level = resolveDimension(accountPos.dimension)
					if (level != null) {
						player.teleportTo(level, accountPos.x, accountPos.y, accountPos.z, emptySet(), accountPos.yaw, accountPos.pitch, false)
					}
				}
				// Refresh session expiry
				val expiresAt = System.currentTimeMillis() + (config.sessionDurationMinutes * 60 * 1000)
				database.saveSession(linkedAccount.id, ip, expiresAt)
				player.sendSystemMessage(Component.empty())
				player.sendSystemMessage(Component.literal("§aSession restored. Welcome back, §e${linkedAccount.username}§a!"))
				player.sendSystemMessage(Component.empty())
				return
			}
		}

		// Save original position and make player invisible/invulnerable in the sky
		// Check if there's a persisted spawn position from a previous unauthenticated disconnect
		val savedPos = database.loadSpawnPosition(player.uuid)
		val dimension = player.level().dimension().identifier().toString()
		val originalPos = if (savedPos != null) {
			SpawnPos(savedPos.x, savedPos.y, savedPos.z, savedPos.dimension)
		} else {
			SpawnPos(player.x, player.y, player.z, dimension)
		}
		spawnPositions[player.uuid] = originalPos
		if (savedPos == null) {
			val px = player.x; val py = player.y; val pz = player.z
			runAsyncFire { database.saveSpawnPosition(player.uuid, px, py, pz, dimension) }
		}
		player.setInvisible(true)
		player.setInvulnerable(true)

		// Clear inventory while unauthenticated (will be restored on login)
		player.inventory.clearContent()
		player.enderChestInventory.clearContent()
		player.containerMenu.broadcastChanges()
		player.inventoryMenu.broadcastChanges()

		player.teleportTo(player.x, config.skyY, player.z)

		player.sendSystemMessage(Component.empty())
		player.sendSystemMessage(Component.literal("§7You have §c${config.authTimeoutSeconds} seconds §7to authenticate."))
		player.sendSystemMessage(Component.literal("§7Use §a/register <invite_code> <username> <password>"))
		player.sendSystemMessage(Component.literal("§7  or §a/login <password>"))
		player.sendSystemMessage(Component.literal("§7  or §a/login_as <username> <password>"))
		player.sendSystemMessage(Component.empty())

		startKickTimer(player, server)
	}

	fun onPlayerDisconnect(player: ServerPlayer) {
		cancelKickTimer(player.uuid)

		// Save inventory and position for authenticated players before disconnect
		val account = accountMap.remove(player.uuid)
		if (account != null && isAuthenticated(player.uuid)) {
			saveAccountPosition(player, account)
			savePlayerInventory(player, account)
			activeAccountSessions.remove(account.id, player.uuid)
		}

		// Restore player state before disconnect so playerdata isn't saved in the sky
		if (!isAuthenticated(player.uuid)) {
			player.setInvisible(false)
			player.setInvulnerable(false)
			val pos = spawnPositions[player.uuid]
			if (pos != null) {
				val level = resolveDimension(pos.dimension)
				if (level != null) {
					player.teleportTo(level, pos.x, pos.y, pos.z, emptySet(), player.yRot, player.xRot, false)
				} else {
					player.teleportTo(pos.x, pos.y, pos.z)
				}
			}
			// Keep the spawn position in the database for the next login
		} else {
			// Player was authenticated, clean up persisted spawn position
			runAsyncFire { database.deleteSpawnPosition(player.uuid) }
		}
		authStates.remove(player.uuid)
		loginAttempts.remove(player.uuid)
		spawnPositions.remove(player.uuid)
	}

	private fun kickExistingSession(account: AuthAccount, newPlayerUuid: UUID) {
		val existingPlayerUuid = activeAccountSessions[account.id]
		if (existingPlayerUuid != null && existingPlayerUuid != newPlayerUuid) {
			val existingPlayer = server?.playerList?.getPlayer(existingPlayerUuid)
 			if (existingPlayer != null) {
				// Save existing player's inventory and position before kicking
				if (isAuthenticated(existingPlayerUuid)) {
					saveAccountPosition(existingPlayer, account)
					savePlayerInventory(existingPlayer, account)
				}
				authStates.remove(existingPlayerUuid)
				accountMap.remove(existingPlayerUuid)
				activeAccountSessions.remove(account.id)
				cancelKickTimer(existingPlayerUuid)
				loginAttempts.remove(existingPlayerUuid)
				spawnPositions.remove(existingPlayerUuid)
				existingPlayer.connection.disconnect(
					Component.literal("§cYour account was logged in from another session.")
				)
			}
		}
	}

	/**
	 * Prepares an already-authenticated player for switching to a different account.
	 * Saves the current account's inventory and cleans up the old session mapping.
	 */
	fun prepareAccountSwitch(player: ServerPlayer) {
		val oldAccount = accountMap[player.uuid]
		if (oldAccount != null) {
			saveAccountPosition(player, oldAccount)
			savePlayerInventory(player, oldAccount)
			activeAccountSessions.remove(oldAccount.id, player.uuid)
		}
	}

	fun onAuthenticated(player: ServerPlayer, account: AuthAccount) {
		kickExistingSession(account, player.uuid)

		authStates[player.uuid] = AuthState.AUTHENTICATED
		accountMap[player.uuid] = account
		activeAccountSessions[account.id] = player.uuid
		cancelKickTimer(player.uuid)
		loginAttempts.remove(player.uuid)

		runAsyncFire { database.linkMinecraftAccount(player.uuid, account.id) }

		player.customName = Component.literal(account.username)
		player.isCustomNameVisible = true

		// Swap GameProfile so the nametag above the head shows the auth username
		updateGameProfileName(player, account.username)

		// Teleport player to their saved position BEFORE restoring inventory,
		// so items don't briefly exist at the sky holding position
		player.setInvisible(false)
		val accountPos = database.loadAccountPosition(account.id)
		if (accountPos != null) {
			val level = resolveDimension(accountPos.dimension)
			if (level != null) {
				player.teleportTo(
					level,
					accountPos.x, accountPos.y, accountPos.z,
					emptySet(), accountPos.yaw, accountPos.pitch, false
				)
			} else {
				player.teleportTo(
					player.level() as net.minecraft.server.level.ServerLevel,
					accountPos.x, accountPos.y, accountPos.z,
					emptySet(), accountPos.yaw, accountPos.pitch, false
				)
			}
		} else {
			val pos = spawnPositions.remove(player.uuid)
			if (pos != null) {
				val level = resolveDimension(pos.dimension)
				if (level != null) {
					player.teleportTo(level, pos.x, pos.y, pos.z, emptySet(), player.yRot, player.xRot, false)
				} else {
					player.teleportTo(pos.x, pos.y, pos.z)
				}
			}
		}
		spawnPositions.remove(player.uuid)
		runAsyncFire { database.deleteSpawnPosition(player.uuid) }
		// Reset velocity and fall distance to prevent fall damage from sky teleport
		player.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
		player.resetFallDistance()

		// Restore inventory AFTER teleporting to the correct position
		loadPlayerInventory(player, account)

		player.setInvulnerable(false)

		// Store session for "remember me" feature
		storeSessionForPlayer(player, account)
	}

	/**
	 * Checks if a registration attempt from the given IP is allowed.
	 * Returns null if allowed, or an error message Component if denied.
	 */
	fun checkRegisterRateLimit(player: ServerPlayer): Component? {
		val ip = extractAddress(player) ?: return null // can't rate-limit without IP

		// Check cooldown
		val cooldownExpiry = registerCooldowns[ip]
		if (cooldownExpiry != null) {
			if (System.currentTimeMillis() < cooldownExpiry) {
				val remaining = (cooldownExpiry - System.currentTimeMillis()) / 1000
				return Component.literal("§cToo many registration attempts. Try again in ${remaining}s.")
			} else {
				registerCooldowns.remove(ip)
				registerAttempts.remove(ip)
			}
		}

		// Check max accounts per IP
		if (config.maxAccountsPerIp > 0) {
			val count = database.countAccountsByIp(ip)
			if (count >= config.maxAccountsPerIp) {
				return Component.literal("§cMaximum number of accounts (${config.maxAccountsPerIp}) reached for your IP address.")
			}
		}

		return null
	}

	/**
	 * Records a registration attempt from the given player's IP.
	 * If the limit is exceeded, a cooldown is applied.
	 */
	fun recordRegisterAttempt(player: ServerPlayer) {
		val ip = extractAddress(player) ?: return
		val now = System.currentTimeMillis()
		val (count, firstAttempt) = registerAttempts.getOrDefault(ip, Pair(0, now))
		val newCount = count + 1
		registerAttempts[ip] = Pair(newCount, firstAttempt)

		if (newCount >= config.maxRegisterAttemptsPerIp) {
			registerCooldowns[ip] = now + (config.registerCooldownSeconds * 1000)
			registerAttempts.remove(ip)
		}
	}

	/**
	 * Records a successful registration from the given player's IP for account-per-IP tracking.
	 */
	fun recordRegistrationIp(player: ServerPlayer, accountId: UUID) {
		val ip = extractAddress(player) ?: return
		runAsyncFire { database.saveRegistrationIp(accountId, ip) }
	}

	/**
	 * Extracts the IP address from a player connection (public for use in commands).
	 */
	fun getPlayerIp(player: ServerPlayer): String? = extractAddress(player)

	fun freezePlayer(player: ServerPlayer) {
		val pos = spawnPositions[player.uuid] ?: return
		// Keep unauthenticated players frozen at sky position with zero velocity
		player.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
		player.resetFallDistance()
		if (player.x != pos.x || player.y != config.skyY || player.z != pos.z) {
			player.teleportTo(pos.x, config.skyY, pos.z)
		}
	}

	fun isSoftBanned(address: String): Component? {
		val banExpiry = database.getActiveSoftBan(address) ?: return null
		val remaining = (banExpiry - System.currentTimeMillis()) / 1000
		return Component.literal("§cYou are temporarily banned. Try again in ${remaining}s.")
	}

	private fun softBan(player: ServerPlayer) {
		val address = extractAddress(player)
		if (address != null) {
			val expiresAt = System.currentTimeMillis() + (config.softBanMinutes * 60 * 1000)
			runAsyncFire { database.saveSoftBan(address, expiresAt) }
		}
	}

	private fun extractAddress(player: ServerPlayer): String? {
		return try {
			val connField = player.connection.javaClass.superclass.getDeclaredField("connection")
			connField.isAccessible = true
			val connection = connField.get(player.connection) as net.minecraft.network.Connection
			val address = connection.remoteAddress?.toString()?.substringBefore(":")?.removePrefix("/")
			address
		} catch (e: Exception) {
			OfflineAuth.LOGGER.warn("Failed to extract IP address for player ${player.gameProfile.name}", e)
			null
		}
	}

	private fun startKickTimer(player: ServerPlayer, server: MinecraftServer) {
		val warnings = mutableListOf<ScheduledFuture<*>>()
		val timeout = config.authTimeoutSeconds
		val warningDelays = listOf(timeout - 30, timeout - 15, timeout - 10, timeout - 5).filter { it > 0 }

		for (delay in warningDelays) {
			val warning = scheduler.schedule({
				server.execute {
					if (!isAuthenticated(player.uuid) && player.isAlive) {
						val remaining = config.authTimeoutSeconds - delay
						player.sendSystemMessage(
							Component.literal("§c⚠ $remaining seconds remaining to authenticate!")
						)
					}
				}
			}, delay, TimeUnit.SECONDS)
			warnings.add(warning)
		}
		warningTimers[player.uuid] = warnings

		val timer = scheduler.schedule({
			server.execute {
				if (!isAuthenticated(player.uuid)) {
					softBan(player)
					player.connection.disconnect(
						Component.literal("§cAuthentication timeout. You did not log in within ${config.authTimeoutSeconds} seconds.")
					)
				}
			}
		}, config.authTimeoutSeconds, TimeUnit.SECONDS)
		kickTimers[player.uuid] = timer
	}

	private fun cancelKickTimer(uuid: UUID) {
		kickTimers.remove(uuid)?.cancel(false)
		warningTimers.remove(uuid)?.forEach { it.cancel(false) }
	}

	// --- Inventory serialization ---

	// Save session after successful authentication
	fun storeSessionForPlayer(player: ServerPlayer, account: AuthAccount) {
		if (config.sessionPersistenceEnabled) {
			val ip = extractAddress(player)
			if (ip != null) {
				val expiresAt = System.currentTimeMillis() + (config.sessionDurationMinutes * 60 * 1000)
				runAsyncFire {
					database.saveSession(account.id, ip, expiresAt)
					OfflineAuth.LOGGER.info("Saved session for ${account.username} from IP $ip (expires in ${config.sessionDurationMinutes} min)")
				}
			} else {
				OfflineAuth.LOGGER.warn("Could not save session for ${account.username}: failed to extract IP address")
			}
		}
	}

	private fun resolveDimension(dimensionKey: String): net.minecraft.server.level.ServerLevel? {
		val srv = server ?: return null
		val id = Identifier.tryParse(dimensionKey) ?: return null
		val key = ResourceKey.create(Registries.DIMENSION, id)
		return srv.getLevel(key)
	}

	private fun saveAccountPosition(player: ServerPlayer, account: AuthAccount) {
		val dimension = player.level().dimension().identifier().toString()
		val x = player.x; val y = player.y; val z = player.z
		val yaw = player.yRot; val pitch = player.xRot
		runAsyncFire { database.saveAccountPosition(account.id, x, y, z, yaw, pitch, dimension) }
	}

	private fun savePlayerInventory(player: ServerPlayer, account: AuthAccount) {
		try {
			val registryAccess = server!!.registryAccess()
			val reporter = ProblemReporter.DISCARDING

			// Serialize inventory
			val invOutput = TagValueOutput.createWithContext(reporter, registryAccess)
			player.inventory.save(invOutput.list("Items", ItemStackWithSlot.CODEC))
			val invTag = invOutput.buildResult()
			val invBytes = compressTag(invTag)

			// Serialize ender chest
			val ecOutput = TagValueOutput.createWithContext(reporter, registryAccess)
			player.enderChestInventory.storeAsSlots(ecOutput.list("Items", ItemStackWithSlot.CODEC))
			val ecTag = ecOutput.buildResult()
			val ecBytes = compressTag(ecTag)

			// Offload the DB write after serialization is done on the main thread
			runAsyncFire { database.savePlayerData(account.id, invBytes, ecBytes) }
		} catch (e: Exception) {
			OfflineAuth.LOGGER.error("Failed to save inventory for account ${account.username}", e)
		}
	}

	private fun loadPlayerInventory(player: ServerPlayer, account: AuthAccount) {
		try {
			val data = database.loadPlayerData(account.id) ?: return
			val (invBytes, ecBytes) = data
			val registryAccess = server!!.registryAccess()
			val reporter = ProblemReporter.DISCARDING

			// Load inventory
			if (invBytes != null) {
				val invTag = decompressTag(invBytes)
				val invInput = TagValueInput.create(reporter, registryAccess, invTag)
				player.inventory.clearContent()
				player.inventory.load(invInput.listOrEmpty("Items", ItemStackWithSlot.CODEC))
			}

			// Load ender chest
			if (ecBytes != null) {
				val ecTag = decompressTag(ecBytes)
				val ecInput = TagValueInput.create(reporter, registryAccess, ecTag)
				player.enderChestInventory.clearContent()
				player.enderChestInventory.fromSlots(ecInput.listOrEmpty("Items", ItemStackWithSlot.CODEC))
			}

			// Sync to client
			player.containerMenu.broadcastChanges()
			player.inventoryMenu.broadcastChanges()
		} catch (e: Exception) {
			OfflineAuth.LOGGER.error("Failed to load inventory for account ${account.username}", e)
		}
	}

	private fun compressTag(tag: CompoundTag): ByteArray {
		val baos = ByteArrayOutputStream()
		DataOutputStream(baos).use { dos ->
			NbtIo.write(tag, dos)
		}
		return baos.toByteArray()
	}

	private fun decompressTag(bytes: ByteArray): CompoundTag {
		val bais = ByteArrayInputStream(bytes)
		return DataInputStream(bais).use { dis ->
			NbtIo.read(dis)
		}
	}

	private fun updateGameProfileName(player: ServerPlayer, newName: String) {
		val oldProfile = player.gameProfile
		val newProfile = GameProfile(oldProfile.id, newName)
		for ((key, value) in oldProfile.properties.entries()) {
			newProfile.properties.put(key, value)
		}
		(player as GameProfileAccessor).setGameProfile(newProfile)

		// Resend player info and respawn entity to all clients so the nametag updates
		val srv = server ?: return
		val infoRemovePacket = ClientboundPlayerInfoRemovePacket(listOf(player.uuid))
		val infoAddPacket = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(listOf(player))
		val entityRemovePacket = ClientboundRemoveEntitiesPacket(player.id)
		val entityAddPacket = ClientboundAddEntityPacket(
			player.id, player.uuid,
			player.x, player.y, player.z,
			player.xRot, player.yRot,
			player.type, 0,
			player.deltaMovement,
			player.yHeadRot.toDouble()
		)
		val entityData = player.entityData.nonDefaultValues
		for (other in srv.playerList.players) {
			if (other === player) {
				// For the player themselves, only update the tab list info
				other.connection.send(infoRemovePacket)
				other.connection.send(infoAddPacket)
			} else {
				// For other players, also respawn the entity to refresh the nametag
				other.connection.send(entityRemovePacket)
				other.connection.send(infoRemovePacket)
				other.connection.send(infoAddPacket)
				other.connection.send(entityAddPacket)
				if (entityData != null) {
					other.connection.send(ClientboundSetEntityDataPacket(player.id, entityData))
				}
			}
		}
	}

	/**
	 * Runs a block asynchronously on the IO executor, then executes the callback on the main server thread.
	 * Use this to offload BCrypt hashing and database operations off the main/network thread.
	 */
	fun <T> runAsync(asyncWork: () -> T, onMainThread: (T) -> Unit) {
		val srv = server ?: return
		ioExecutor.submit {
			try {
				val result = asyncWork()
				srv.execute { onMainThread(result) }
			} catch (e: Exception) {
				OfflineAuth.LOGGER.error("Async operation failed", e)
			}
		}
	}

	/**
	 * Runs a block asynchronously on the IO executor with no main-thread callback.
	 * Use this for fire-and-forget database writes.
	 */
	fun runAsyncFire(asyncWork: () -> Unit) {
		ioExecutor.submit {
			try {
				asyncWork()
			} catch (e: Exception) {
				OfflineAuth.LOGGER.error("Async fire-and-forget operation failed", e)
			}
		}
	}

	fun shutdown() {
		scheduler.shutdownNow()
		ioExecutor.shutdown()
		try {
			if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				ioExecutor.shutdownNow()
			}
		} catch (_: InterruptedException) {
			ioExecutor.shutdownNow()
		}
		database.close()
	}
}
