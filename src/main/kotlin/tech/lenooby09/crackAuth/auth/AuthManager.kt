package tech.lenooby09.crackAuth.auth

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
import tech.lenooby09.crackAuth.CrackAuth
import tech.lenooby09.crackAuth.config.CrackAuthConfig
import tech.lenooby09.crackAuth.mixin.GameProfileAccessor
import tech.lenooby09.crackAuth.storage.DatabaseManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AuthManager(val database: DatabaseManager, val config: CrackAuthConfig) {

	val authStates = ConcurrentHashMap<UUID, AuthState>()
	val accountMap = ConcurrentHashMap<UUID, AuthAccount>()
	val loginAttempts = ConcurrentHashMap<UUID, Int>()

	private val kickTimers = ConcurrentHashMap<UUID, ScheduledFuture<*>>()
	private val warningTimers = ConcurrentHashMap<UUID, MutableList<ScheduledFuture<*>>>()
	private val scheduler = Executors.newScheduledThreadPool(1)

	private val softBans = ConcurrentHashMap<String, Long>()

	// Reverse mapping: account ID -> currently logged-in player UUID
	private val activeAccountSessions = ConcurrentHashMap<UUID, UUID>()

	private var server: MinecraftServer? = null

	private val spawnPositions = ConcurrentHashMap<UUID, Triple<Double, Double, Double>>()


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

		// Save original position and make player invisible/invulnerable in the sky
		spawnPositions[player.uuid] = Triple(player.x, player.y, player.z)
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

		// Save inventory for authenticated players before disconnect
		val account = accountMap.remove(player.uuid)
		if (account != null && isAuthenticated(player.uuid)) {
			savePlayerInventory(player, account)
			activeAccountSessions.remove(account.id, player.uuid)
		}

		// Restore player state before disconnect so playerdata isn't saved in the sky
		if (!isAuthenticated(player.uuid)) {
			player.setInvisible(false)
			player.setInvulnerable(false)
			val pos = spawnPositions[player.uuid]
			if (pos != null) {
				player.teleportTo(pos.first, pos.second, pos.third)
			}
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
				// Save existing player's inventory before kicking
				if (isAuthenticated(existingPlayerUuid)) {
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

	fun onAuthenticated(player: ServerPlayer, account: AuthAccount) {
		kickExistingSession(account, player.uuid)

		authStates[player.uuid] = AuthState.AUTHENTICATED
		accountMap[player.uuid] = account
		activeAccountSessions[account.id] = player.uuid
		cancelKickTimer(player.uuid)
		loginAttempts.remove(player.uuid)

		database.linkMinecraftAccount(player.uuid, account.id)

		player.customName = Component.literal(account.username)
		player.isCustomNameVisible = true

		// Swap GameProfile so the nametag above the head shows the auth username
		updateGameProfileName(player, account.username)

		// Restore inventory from account data
		loadPlayerInventory(player, account)

		// Restore player: visible, vulnerable, back to original position
		player.setInvisible(false)
		val pos = spawnPositions.remove(player.uuid)
		if (pos != null) {
			player.teleportTo(pos.first, pos.second, pos.third)
		}
		// Reset velocity and fall distance to prevent fall damage from sky teleport
		player.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
		player.resetFallDistance()
		player.setInvulnerable(false)
	}

	fun freezePlayer(player: ServerPlayer) {
		val pos = spawnPositions[player.uuid] ?: return
		// Keep unauthenticated players frozen at sky position with zero velocity
		player.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
		player.resetFallDistance()
		if (player.x != pos.first || player.y != config.skyY || player.z != pos.third) {
			player.teleportTo(pos.first, config.skyY, pos.third)
		}
	}

	fun isSoftBanned(address: String): Component? {
		val banExpiry = softBans[address] ?: return null
		if (System.currentTimeMillis() < banExpiry) {
			val remaining = (banExpiry - System.currentTimeMillis()) / 1000
			return Component.literal("§cYou are temporarily banned. Try again in ${remaining}s.")
		} else {
			softBans.remove(address)
			return null
		}
	}

	private fun softBan(player: ServerPlayer) {
		val address = extractAddress(player)
		if (address != null) {
			softBans[address] = System.currentTimeMillis() + (config.softBanMinutes * 60 * 1000)
		}
	}

	private fun extractAddress(player: ServerPlayer): String? {
		return try {
			val connField = player.connection.javaClass.superclass.getDeclaredField("connection")
			connField.isAccessible = true
			val connection = connField.get(player.connection) as net.minecraft.network.Connection
			connection.remoteAddress?.toString()?.substringBefore(":")?.removePrefix("/")
		} catch (_: Exception) {
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

			database.savePlayerData(account.id, invBytes, ecBytes)
		} catch (e: Exception) {
			CrackAuth.LOGGER.error("Failed to save inventory for account ${account.username}", e)
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
			CrackAuth.LOGGER.error("Failed to load inventory for account ${account.username}", e)
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

	fun shutdown() {
		scheduler.shutdownNow()
	}
}
