package tech.lenooby09.offlineAuth.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import tech.lenooby09.offlineAuth.auth.AuthAccount
import tech.lenooby09.offlineAuth.config.OfflineAuthConfig
import java.nio.file.Path
import java.sql.Connection
import java.util.*

enum class DatabaseType {
	SQLITE, POSTGRESQL
}

class DatabaseManager {

	private val dataSource: HikariDataSource
	private val dbType: DatabaseType

	constructor(dbPath: Path) {
		dbType = DatabaseType.SQLITE
		Class.forName("org.sqlite.JDBC")
		val hikariConfig = HikariConfig().apply {
			jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
			maximumPoolSize = 10
			minimumIdle = 2
			connectionTimeout = 5000
			idleTimeout = 60000
			maxLifetime = 300000
			addDataSourceProperty("journal_mode", "WAL")
		}
		dataSource = HikariDataSource(hikariConfig)

		connection().use { conn ->
			conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
		}

		createTables()
		migrateSchema()
	}

	constructor(config: OfflineAuthConfig) {
		dbType = DatabaseType.POSTGRESQL
		Class.forName("org.postgresql.Driver")
		val hikariConfig = HikariConfig().apply {
			jdbcUrl = "jdbc:postgresql://${config.postgresHost}:${config.postgresPort}/${config.postgresDatabase}"
			username = config.postgresUser
			password = config.postgresPassword
			maximumPoolSize = 10
			minimumIdle = 2
			connectionTimeout = 5000
			idleTimeout = 60000
			maxLifetime = 300000
		}
		dataSource = HikariDataSource(hikariConfig)

		createTables()
		migrateSchema()
	}

	private fun connection(): Connection = dataSource.connection

	private val blobType get() = if (dbType == DatabaseType.SQLITE) "BLOB" else "BYTEA"

	private fun upsertSql(table: String, conflictCol: String, columns: List<String>, updateColumns: List<String>): String {
		val cols = columns.joinToString(", ")
		val placeholders = columns.joinToString(", ") { "?" }
		val updateSet = updateColumns.joinToString(", ") { "$it = EXCLUDED.$it" }
		return "INSERT INTO $table ($cols) VALUES ($placeholders) ON CONFLICT($conflictCol) DO UPDATE SET $updateSet"
	}

	private fun upsertSql(table: String, conflictCols: List<String>, columns: List<String>, updateColumns: List<String>): String {
		val cols = columns.joinToString(", ")
		val placeholders = columns.joinToString(", ") { "?" }
		val conflict = conflictCols.joinToString(", ")
		val updateSet = updateColumns.joinToString(", ") { "$it = EXCLUDED.$it" }
		return "INSERT INTO $table ($cols) VALUES ($placeholders) ON CONFLICT($conflict) DO UPDATE SET $updateSet"
	}

	private fun createTables() {
		connection().use { conn ->
			conn.createStatement().use { stmt ->
				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    registered_at BIGINT NOT NULL,
                    is_dashboard_admin INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS invite_codes (
                    code TEXT PRIMARY KEY,
                    created_by TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    max_uses INTEGER NOT NULL DEFAULT 1,
                    current_uses INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS invite_code_uses (
                    code TEXT NOT NULL,
                    used_by TEXT NOT NULL,
                    used_at BIGINT NOT NULL,
                    FOREIGN KEY (code) REFERENCES invite_codes(code)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS account_links (
                    minecraft_uuid TEXT NOT NULL,
                    account_id TEXT NOT NULL,
                    PRIMARY KEY (minecraft_uuid),
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS player_data (
                    account_id TEXT PRIMARY KEY,
                    inventory_data $blobType,
                    ender_chest_data $blobType,
                    equipment_data $blobType,
                    exp INTEGER NOT NULL DEFAULT 0,
                    exp_level INTEGER NOT NULL DEFAULT 0,
                    exp_progress REAL NOT NULL DEFAULT 0.0,
                    health REAL NOT NULL DEFAULT 20.0,
                    food_level INTEGER NOT NULL DEFAULT 20,
                    saturation REAL NOT NULL DEFAULT 5.0,
                    game_mode TEXT NOT NULL DEFAULT 'SURVIVAL',
                    selected_slot INTEGER NOT NULL DEFAULT 0,
                    effects_data $blobType,
                    is_flying INTEGER NOT NULL DEFAULT 0,
                    respawn_data $blobType,
                    recipes_data $blobType,
                    advancements_data $blobType,
                    stats_data $blobType,
                    food_exhaustion REAL NOT NULL DEFAULT 0.0,
                    food_tick_timer INTEGER NOT NULL DEFAULT 0,
                    score INTEGER NOT NULL DEFAULT 0,
                    fire_ticks INTEGER NOT NULL DEFAULT 0,
                    air_supply INTEGER NOT NULL DEFAULT 300,
                    is_op INTEGER NOT NULL DEFAULT 0,
                    op_level INTEGER NOT NULL DEFAULT 0,
                    updated_at BIGINT NOT NULL,
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS spawn_positions (
                    minecraft_uuid TEXT PRIMARY KEY,
                    x DOUBLE PRECISION NOT NULL,
                    y DOUBLE PRECISION NOT NULL,
                    z DOUBLE PRECISION NOT NULL,
                    dimension TEXT NOT NULL DEFAULT 'minecraft:overworld'
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS account_positions (
                    account_id TEXT PRIMARY KEY,
                    x DOUBLE PRECISION NOT NULL,
                    y DOUBLE PRECISION NOT NULL,
                    z DOUBLE PRECISION NOT NULL,
                    yaw REAL NOT NULL DEFAULT 0,
                    pitch REAL NOT NULL DEFAULT 0,
                    dimension TEXT NOT NULL DEFAULT 'minecraft:overworld',
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS registration_ips (
                    account_id TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    registered_at BIGINT NOT NULL,
                    PRIMARY KEY (account_id),
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS auth_sessions (
                    account_id TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    PRIMARY KEY (account_id, ip_address),
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS login_attempts (
                    account_id TEXT PRIMARY KEY,
                    failed_count INTEGER NOT NULL DEFAULT 0,
                    last_failed_at BIGINT NOT NULL DEFAULT 0,
                    locked_until BIGINT NOT NULL DEFAULT 0,
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS soft_bans (
                    ip_address TEXT PRIMARY KEY,
                    expires_at BIGINT NOT NULL
                )
                """.trimIndent()
				)
			}
		}
	}

	private fun getTableColumns(conn: Connection, tableName: String): Set<String> {
		val columns = mutableSetOf<String>()
		if (dbType == DatabaseType.SQLITE) {
			conn.createStatement().use { stmt ->
				val rs = stmt.executeQuery("PRAGMA table_info($tableName)")
				while (rs.next()) {
					columns.add(rs.getString("name"))
				}
			}
		} else {
			conn.prepareStatement(
				"SELECT column_name FROM information_schema.columns WHERE table_name = ?"
			).use { stmt ->
				stmt.setString(1, tableName)
				val rs = stmt.executeQuery()
				while (rs.next()) {
					columns.add(rs.getString("column_name"))
				}
			}
		}
		return columns
	}

	private fun addColumnIfMissing(conn: Connection, table: String, column: String, definition: String, columns: Set<String>) {
		if (columns.isNotEmpty() && column !in columns) {
			conn.createStatement().use { stmt ->
				stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
			}
		}
	}

	private fun migrateSchema() {
		connection().use { conn ->
			// Migrate account_positions
			val apColumns = getTableColumns(conn, "account_positions")
			addColumnIfMissing(conn, "account_positions", "yaw", "REAL NOT NULL DEFAULT 0", apColumns)
			addColumnIfMissing(conn, "account_positions", "pitch", "REAL NOT NULL DEFAULT 0", apColumns)
			addColumnIfMissing(conn, "account_positions", "dimension", "TEXT NOT NULL DEFAULT 'minecraft:overworld'", apColumns)

			// Migrate spawn_positions
			val spColumns = getTableColumns(conn, "spawn_positions")
			addColumnIfMissing(conn, "spawn_positions", "dimension", "TEXT NOT NULL DEFAULT 'minecraft:overworld'", spColumns)

			migratePlayerDataSchema(conn)

			// Migrate accounts table
			val accColumns = getTableColumns(conn, "accounts")
			addColumnIfMissing(conn, "accounts", "is_dashboard_admin", "INTEGER NOT NULL DEFAULT 0", accColumns)
		}
	}

	private fun migratePlayerDataSchema(conn: Connection) {
		val pdColumns = getTableColumns(conn, "player_data")
		val bt = blobType
		addColumnIfMissing(conn, "player_data", "equipment_data", bt, pdColumns)
		addColumnIfMissing(conn, "player_data", "exp", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "exp_level", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "exp_progress", "REAL NOT NULL DEFAULT 0.0", pdColumns)
		addColumnIfMissing(conn, "player_data", "health", "REAL NOT NULL DEFAULT 20.0", pdColumns)
		addColumnIfMissing(conn, "player_data", "food_level", "INTEGER NOT NULL DEFAULT 20", pdColumns)
		addColumnIfMissing(conn, "player_data", "saturation", "REAL NOT NULL DEFAULT 5.0", pdColumns)
		addColumnIfMissing(conn, "player_data", "game_mode", "TEXT NOT NULL DEFAULT 'SURVIVAL'", pdColumns)
		addColumnIfMissing(conn, "player_data", "selected_slot", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "effects_data", bt, pdColumns)
		addColumnIfMissing(conn, "player_data", "is_flying", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "respawn_data", bt, pdColumns)
		addColumnIfMissing(conn, "player_data", "recipes_data", bt, pdColumns)
		addColumnIfMissing(conn, "player_data", "advancements_data", bt, pdColumns)
		addColumnIfMissing(conn, "player_data", "stats_data", bt, pdColumns)
		addColumnIfMissing(conn, "player_data", "food_exhaustion", "REAL NOT NULL DEFAULT 0.0", pdColumns)
		addColumnIfMissing(conn, "player_data", "food_tick_timer", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "score", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "fire_ticks", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "air_supply", "INTEGER NOT NULL DEFAULT 300", pdColumns)
		addColumnIfMissing(conn, "player_data", "is_op", "INTEGER NOT NULL DEFAULT 0", pdColumns)
		addColumnIfMissing(conn, "player_data", "op_level", "INTEGER NOT NULL DEFAULT 0", pdColumns)
	}

	// --- Account operations ---

	fun saveAccount(account: AuthAccount) {
		connection().use { conn ->
			conn.prepareStatement(
				"INSERT INTO accounts (id, username, password_hash, registered_at, is_dashboard_admin) VALUES (?, ?, ?, ?, ?)"
			).use { stmt ->
				stmt.setString(1, account.id.toString())
				stmt.setString(2, account.username)
				stmt.setString(3, account.passwordHash)
				stmt.setLong(4, account.registeredAt)
				stmt.setInt(5, if (account.isDashboardAdmin) 1 else 0)
				stmt.executeUpdate()
			}
		}
	}

	fun getAccountByUsername(username: String): AuthAccount? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT id, username, password_hash, registered_at, is_dashboard_admin FROM accounts WHERE LOWER(username) = LOWER(?)"
			).use { stmt ->
				stmt.setString(1, username)
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return AuthAccount(
						id = UUID.fromString(rs.getString("id")),
						username = rs.getString("username"),
						passwordHash = rs.getString("password_hash"),
						registeredAt = rs.getLong("registered_at"),
						isDashboardAdmin = rs.getInt("is_dashboard_admin") == 1,
					)
				}
			}
		}
		return null
	}

	fun getAccountByMinecraftUUID(minecraftUuid: UUID): AuthAccount? {
		connection().use { conn ->
			conn.prepareStatement(
				"""
            SELECT a.id, a.username, a.password_hash, a.registered_at, a.is_dashboard_admin
            FROM accounts a
            JOIN account_links l ON a.id = l.account_id
            WHERE l.minecraft_uuid = ?
            """.trimIndent()
			).use { stmt ->
				stmt.setString(1, minecraftUuid.toString())
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return AuthAccount(
						id = UUID.fromString(rs.getString("id")),
						username = rs.getString("username"),
						passwordHash = rs.getString("password_hash"),
						registeredAt = rs.getLong("registered_at"),
						isDashboardAdmin = rs.getInt("is_dashboard_admin") == 1,
					)
				}
			}
		}
		return null
	}

	fun linkMinecraftAccount(minecraftUuid: UUID, accountId: UUID) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("account_links", "minecraft_uuid",
					listOf("minecraft_uuid", "account_id"),
					listOf("account_id"))
			).use { stmt ->
				stmt.setString(1, minecraftUuid.toString())
				stmt.setString(2, accountId.toString())
				stmt.executeUpdate()
			}
		}
	}

	// --- Invite code operations ---

	fun saveInviteCode(code: String, createdBy: String, createdAt: Long, maxUses: Int) {
		connection().use { conn ->
			conn.prepareStatement(
				"INSERT INTO invite_codes (code, created_by, created_at, max_uses, current_uses) VALUES (?, ?, ?, ?, 0)"
			).use { stmt ->
				stmt.setString(1, code)
				stmt.setString(2, createdBy)
				stmt.setLong(3, createdAt)
				stmt.setInt(4, maxUses)
				stmt.executeUpdate()
			}
		}
	}

	fun getInviteCode(code: String): InviteCodeRecord? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT code, created_by, created_at, max_uses, current_uses FROM invite_codes WHERE code = ?"
			).use { stmt ->
				stmt.setString(1, code)
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return InviteCodeRecord(
						code = rs.getString("code"),
						createdBy = rs.getString("created_by"),
						createdAt = rs.getLong("created_at"),
						maxUses = rs.getInt("max_uses"),
						currentUses = rs.getInt("current_uses"),
					)
				}
			}
		}
		return null
	}

	fun useInviteCode(code: String, usedBy: String) {
		connection().use { conn ->
			conn.autoCommit = false
			try {
				conn.prepareStatement(
					"UPDATE invite_codes SET current_uses = current_uses + 1 WHERE code = ?"
				).use { stmt ->
					stmt.setString(1, code)
					stmt.executeUpdate()
				}
				conn.prepareStatement(
					"INSERT INTO invite_code_uses (code, used_by, used_at) VALUES (?, ?, ?)"
				).use { stmt ->
					stmt.setString(1, code)
					stmt.setString(2, usedBy)
					stmt.setLong(3, System.currentTimeMillis())
					stmt.executeUpdate()
				}
				conn.commit()
			} catch (e: Exception) {
				conn.rollback()
				throw e
			} finally {
				conn.autoCommit = true
			}
		}
	}

	fun getActiveInviteCodes(): List<InviteCodeRecord> {
		val codes = mutableListOf<InviteCodeRecord>()
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT code, created_by, created_at, max_uses, current_uses FROM invite_codes WHERE current_uses < max_uses"
			).use { stmt ->
				val rs = stmt.executeQuery()
				while (rs.next()) {
					codes.add(
						InviteCodeRecord(
							code = rs.getString("code"),
							createdBy = rs.getString("created_by"),
							createdAt = rs.getLong("created_at"),
							maxUses = rs.getInt("max_uses"),
							currentUses = rs.getInt("current_uses"),
						)
					)
				}
			}
		}
		return codes
	}

	fun revokeInviteCode(code: String): Boolean {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM invite_codes WHERE code = ?"
			).use { stmt ->
				stmt.setString(1, code)
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun savePlayerData(
		accountId: UUID,
		inventoryData: ByteArray?,
		enderChestData: ByteArray?,
		equipmentData: ByteArray?,
		exp: Int,
		expLevel: Int,
		expProgress: Float,
		health: Float,
		foodLevel: Int,
		saturation: Float,
		gameMode: String,
		selectedSlot: Int,
		effectsData: ByteArray?,
		isFlying: Boolean,
		respawnData: ByteArray?,
		recipesData: ByteArray?,
		advancementsData: ByteArray?,
		statsData: ByteArray?,
		foodExhaustion: Float,
		foodTickTimer: Int,
		score: Int,
		fireTicks: Int,
		airSupply: Int,
		isOp: Boolean,
		opLevel: Int,
	) {
		val allCols = listOf("account_id", "inventory_data", "ender_chest_data", "equipment_data", "exp", "exp_level", "exp_progress", "health", "food_level", "saturation", "game_mode", "selected_slot", "effects_data", "is_flying", "respawn_data", "recipes_data", "advancements_data", "stats_data", "food_exhaustion", "food_tick_timer", "score", "fire_ticks", "air_supply", "is_op", "op_level", "updated_at")
		val updateCols = allCols.filter { it != "account_id" }
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("player_data", "account_id", allCols, updateCols)
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setBytes(2, inventoryData)
				stmt.setBytes(3, enderChestData)
				stmt.setBytes(4, equipmentData)
				stmt.setInt(5, exp)
				stmt.setInt(6, expLevel)
				stmt.setFloat(7, expProgress)
				stmt.setFloat(8, health)
				stmt.setInt(9, foodLevel)
				stmt.setFloat(10, saturation)
				stmt.setString(11, gameMode)
				stmt.setInt(12, selectedSlot)
				stmt.setBytes(13, effectsData)
				stmt.setInt(14, if (isFlying) 1 else 0)
				stmt.setBytes(15, respawnData)
				stmt.setBytes(16, recipesData)
				stmt.setBytes(17, advancementsData)
				stmt.setBytes(18, statsData)
				stmt.setFloat(19, foodExhaustion)
				stmt.setInt(20, foodTickTimer)
				stmt.setInt(21, score)
				stmt.setInt(22, fireTicks)
				stmt.setInt(23, airSupply)
				stmt.setInt(24, if (isOp) 1 else 0)
				stmt.setInt(25, opLevel)
				stmt.setLong(26, System.currentTimeMillis())
				stmt.executeUpdate()
			}
		}
	}

	data class PlayerData(
		val inventoryData: ByteArray?,
		val enderChestData: ByteArray?,
		val equipmentData: ByteArray?,
		val exp: Int,
		val expLevel: Int,
		val expProgress: Float,
		val health: Float,
		val foodLevel: Int,
		val saturation: Float,
		val gameMode: String,
		val selectedSlot: Int,
		val effectsData: ByteArray?,
		val isFlying: Boolean,
		val respawnData: ByteArray?,
		val recipesData: ByteArray?,
		val advancementsData: ByteArray?,
		val statsData: ByteArray?,
		val foodExhaustion: Float,
		val foodTickTimer: Int,
		val score: Int,
		val fireTicks: Int,
		val airSupply: Int,
		val isOp: Boolean,
		val opLevel: Int,
	)

	fun loadPlayerData(accountId: UUID): PlayerData? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT inventory_data, ender_chest_data, equipment_data, exp, exp_level, exp_progress, health, food_level, saturation, game_mode, selected_slot, effects_data, is_flying, respawn_data, recipes_data, advancements_data, stats_data, food_exhaustion, food_tick_timer, score, fire_ticks, air_supply, is_op, op_level FROM player_data WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return PlayerData(
						inventoryData = rs.getBytes("inventory_data"),
						enderChestData = rs.getBytes("ender_chest_data"),
						equipmentData = rs.getBytes("equipment_data"),
						exp = rs.getInt("exp"),
						expLevel = rs.getInt("exp_level"),
						expProgress = rs.getFloat("exp_progress"),
						health = rs.getFloat("health"),
						foodLevel = rs.getInt("food_level"),
						saturation = rs.getFloat("saturation"),
						gameMode = rs.getString("game_mode"),
						selectedSlot = rs.getInt("selected_slot"),
						effectsData = rs.getBytes("effects_data"),
						isFlying = rs.getInt("is_flying") != 0,
						respawnData = rs.getBytes("respawn_data"),
						recipesData = rs.getBytes("recipes_data"),
						advancementsData = rs.getBytes("advancements_data"),
						statsData = rs.getBytes("stats_data"),
						foodExhaustion = rs.getFloat("food_exhaustion"),
						foodTickTimer = rs.getInt("food_tick_timer"),
						score = rs.getInt("score"),
						fireTicks = rs.getInt("fire_ticks"),
						airSupply = rs.getInt("air_supply"),
						isOp = rs.getInt("is_op") != 0,
						opLevel = rs.getInt("op_level"),
					)
				}
			}
		}
		return null
	}

	fun deleteAccountByUsername(username: String): Boolean {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT id FROM accounts WHERE LOWER(username) = LOWER(?)"
			).use { stmt ->
				stmt.setString(1, username)
				val rs = stmt.executeQuery()
				if (!rs.next()) return false

				val accountId = rs.getString("id")
 			conn.prepareStatement("DELETE FROM player_data WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
				conn.prepareStatement("DELETE FROM account_links WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
				conn.prepareStatement("DELETE FROM account_positions WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
				conn.prepareStatement("DELETE FROM registration_ips WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
				conn.prepareStatement("DELETE FROM auth_sessions WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
				conn.prepareStatement("DELETE FROM accounts WHERE id = ?").use { it.setString(1, accountId); it.executeUpdate() }
				return true
			}
		}
	}

	fun getAllUsernames(): List<String> {
		val names = mutableListOf<String>()
		connection().use { conn ->
			conn.createStatement().use { stmt ->
				val rs = stmt.executeQuery("SELECT username FROM accounts")
				while (rs.next()) {
					names.add(rs.getString("username"))
				}
			}
		}
		return names
	}

	fun setAccountOp(accountId: UUID, isOp: Boolean, opLevel: Int) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("player_data", "account_id",
					listOf("account_id", "is_op", "op_level", "updated_at"),
					listOf("is_op", "op_level", "updated_at"))
			).use { stmt ->
				val now = System.currentTimeMillis()
				stmt.setString(1, accountId.toString())
				stmt.setInt(2, if (isOp) 1 else 0)
				stmt.setInt(3, opLevel)
				stmt.setLong(4, now)
				stmt.executeUpdate()
			}
		}
	}

	fun getAccountOp(accountId: UUID): Boolean {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT is_op FROM player_data WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				val rs = stmt.executeQuery()
				return rs.next() && rs.getInt("is_op") != 0
			}
		}
	}

	fun hasAnyAccounts(): Boolean {
		connection().use { conn ->
			conn.createStatement().use { stmt ->
				val rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")
				return rs.next() && rs.getInt(1) > 0
			}
		}
	}

	// --- Spawn position operations ---

	data class SpawnPosition(val x: Double, val y: Double, val z: Double, val dimension: String)

	fun saveSpawnPosition(minecraftUuid: UUID, x: Double, y: Double, z: Double, dimension: String) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("spawn_positions", "minecraft_uuid",
					listOf("minecraft_uuid", "x", "y", "z", "dimension"),
					listOf("x", "y", "z", "dimension"))
			).use { stmt ->
				stmt.setString(1, minecraftUuid.toString())
				stmt.setDouble(2, x)
				stmt.setDouble(3, y)
				stmt.setDouble(4, z)
				stmt.setString(5, dimension)
				stmt.executeUpdate()
			}
		}
	}

	fun loadSpawnPosition(minecraftUuid: UUID): SpawnPosition? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT x, y, z, dimension FROM spawn_positions WHERE minecraft_uuid = ?"
			).use { stmt ->
				stmt.setString(1, minecraftUuid.toString())
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return SpawnPosition(
						rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
						rs.getString("dimension")
					)
				}
			}
		}
		return null
	}

	fun deleteSpawnPosition(minecraftUuid: UUID) {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM spawn_positions WHERE minecraft_uuid = ?"
			).use { stmt ->
				stmt.setString(1, minecraftUuid.toString())
				stmt.executeUpdate()
			}
		}
	}

	// --- Account position operations ---

	fun saveAccountPosition(accountId: UUID, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, dimension: String) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("account_positions", "account_id",
					listOf("account_id", "x", "y", "z", "yaw", "pitch", "dimension"),
					listOf("x", "y", "z", "yaw", "pitch", "dimension"))
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setDouble(2, x)
				stmt.setDouble(3, y)
				stmt.setDouble(4, z)
				stmt.setFloat(5, yaw)
				stmt.setFloat(6, pitch)
				stmt.setString(7, dimension)
				stmt.executeUpdate()
			}
		}
	}

	data class AccountPosition(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val dimension: String)

	fun loadAccountPosition(accountId: UUID): AccountPosition? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT x, y, z, yaw, pitch, dimension FROM account_positions WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return AccountPosition(
						rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
						rs.getFloat("yaw"), rs.getFloat("pitch"),
						rs.getString("dimension")
					)
				}
			}
		}
		return null
	}

	fun deleteAccountPosition(accountId: UUID) {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM account_positions WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.executeUpdate()
			}
		}
	}

	// --- Session persistence operations ---

	fun saveSession(accountId: UUID, ipAddress: String, expiresAt: Long) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("auth_sessions", listOf("account_id", "ip_address"),
					listOf("account_id", "ip_address", "expires_at"),
					listOf("expires_at"))
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setString(2, ipAddress)
				stmt.setLong(3, expiresAt)
				stmt.executeUpdate()
			}
		}
	}

	fun getValidSession(accountId: UUID, ipAddress: String): Boolean {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT expires_at FROM auth_sessions WHERE account_id = ? AND ip_address = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setString(2, ipAddress)
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return rs.getLong("expires_at") > System.currentTimeMillis()
				}
			}
		}
		return false
	}

	fun deleteSession(accountId: UUID) {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM auth_sessions WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.executeUpdate()
			}
		}
	}

	fun cleanExpiredSessions() {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM auth_sessions WHERE expires_at < ?"
			).use { stmt ->
				stmt.setLong(1, System.currentTimeMillis())
				stmt.executeUpdate()
			}
		}
	}

	// --- Registration IP tracking ---

	fun saveRegistrationIp(accountId: UUID, ipAddress: String) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("registration_ips", "account_id",
					listOf("account_id", "ip_address", "registered_at"),
					listOf("ip_address", "registered_at"))
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setString(2, ipAddress)
				stmt.setLong(3, System.currentTimeMillis())
				stmt.executeUpdate()
			}
		}
	}

	fun countAccountsByIp(ipAddress: String): Int {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT COUNT(*) FROM registration_ips WHERE ip_address = ?"
			).use { stmt ->
				stmt.setString(1, ipAddress)
				val rs = stmt.executeQuery()
				return if (rs.next()) rs.getInt(1) else 0
			}
		}
	}

	// --- Password operations ---

	// --- Login attempt tracking ---

	data class LoginAttemptRecord(
		val failedCount: Int,
		val lastFailedAt: Long,
		val lockedUntil: Long,
	)

	fun getLoginAttempts(accountId: UUID): LoginAttemptRecord? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT failed_count, last_failed_at, locked_until FROM login_attempts WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return LoginAttemptRecord(
						failedCount = rs.getInt("failed_count"),
						lastFailedAt = rs.getLong("last_failed_at"),
						lockedUntil = rs.getLong("locked_until"),
					)
				}
			}
		}
		return null
	}

	fun recordFailedLogin(accountId: UUID, lockedUntil: Long) {
		connection().use { conn ->
			conn.prepareStatement(
				"""
				INSERT INTO login_attempts (account_id, failed_count, last_failed_at, locked_until)
				VALUES (?, 1, ?, ?)
				ON CONFLICT(account_id) DO UPDATE SET
					failed_count = login_attempts.failed_count + 1,
					last_failed_at = EXCLUDED.last_failed_at,
					locked_until = EXCLUDED.locked_until
				""".trimIndent()
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setLong(2, System.currentTimeMillis())
				stmt.setLong(3, lockedUntil)
				stmt.executeUpdate()
			}
		}
	}

	fun resetLoginAttempts(accountId: UUID) {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM login_attempts WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.executeUpdate()
			}
		}
	}

	fun updatePasswordHash(accountId: UUID, newPasswordHash: String) {
		connection().use { conn ->
			conn.prepareStatement(
				"UPDATE accounts SET password_hash = ? WHERE id = ?"
			).use { stmt ->
				stmt.setString(1, newPasswordHash)
				stmt.setString(2, accountId.toString())
				stmt.executeUpdate()
			}
		}
	}

	fun setDashboardAdmin(accountId: UUID, isAdmin: Boolean) {
		connection().use { conn ->
			conn.prepareStatement(
				"UPDATE accounts SET is_dashboard_admin = ? WHERE id = ?"
			).use { stmt ->
				stmt.setInt(1, if (isAdmin) 1 else 0)
				stmt.setString(2, accountId.toString())
				stmt.executeUpdate()
			}
		}
	}

	fun updateUsername(accountId: UUID, newUsername: String): Boolean {
		connection().use { conn ->
			conn.prepareStatement(
				"UPDATE accounts SET username = ? WHERE id = ?"
			).use { stmt ->
				stmt.setString(1, newUsername)
				stmt.setString(2, accountId.toString())
				return stmt.executeUpdate() > 0
			}
		}
	}

	// --- Soft-ban operations ---

	fun saveSoftBan(ipAddress: String, expiresAt: Long) {
		connection().use { conn ->
			conn.prepareStatement(
				upsertSql("soft_bans", "ip_address",
					listOf("ip_address", "expires_at"),
					listOf("expires_at"))
			).use { stmt ->
				stmt.setString(1, ipAddress)
				stmt.setLong(2, expiresAt)
				stmt.executeUpdate()
			}
		}
	}

	fun getActiveSoftBan(ipAddress: String): Long? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT expires_at FROM soft_bans WHERE ip_address = ? AND expires_at > ?"
			).use { stmt ->
				stmt.setString(1, ipAddress)
				stmt.setLong(2, System.currentTimeMillis())
				val rs = stmt.executeQuery()
				return if (rs.next()) rs.getLong("expires_at") else null
			}
		}
	}

	fun removeSoftBan(ipAddress: String) {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM soft_bans WHERE ip_address = ?"
			).use { stmt ->
				stmt.setString(1, ipAddress)
				stmt.executeUpdate()
			}
		}
	}

	fun cleanExpiredSoftBans() {
		connection().use { conn ->
			conn.prepareStatement(
				"DELETE FROM soft_bans WHERE expires_at <= ?"
			).use { stmt ->
				stmt.setLong(1, System.currentTimeMillis())
				stmt.executeUpdate()
			}
		}
	}

	fun getAllAccounts(): List<AuthAccount> {
		val accounts = mutableListOf<AuthAccount>()
		connection().use { conn ->
			conn.createStatement().use { stmt ->
				val rs = stmt.executeQuery("SELECT id, username, password_hash, registered_at, is_dashboard_admin FROM accounts ORDER BY username")
				while (rs.next()) {
					accounts.add(
						AuthAccount(
							id = UUID.fromString(rs.getString("id")),
							username = rs.getString("username"),
							passwordHash = rs.getString("password_hash"),
							registeredAt = rs.getLong("registered_at"),
							isDashboardAdmin = rs.getInt("is_dashboard_admin") == 1,
						)
					)
				}
			}
		}
		return accounts
	}

	data class SoftBanRecord(val ipAddress: String, val expiresAt: Long)

	fun getActiveSoftBans(): List<SoftBanRecord> {
		val bans = mutableListOf<SoftBanRecord>()
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT ip_address, expires_at FROM soft_bans WHERE expires_at > ?"
			).use { stmt ->
				stmt.setLong(1, System.currentTimeMillis())
				val rs = stmt.executeQuery()
				while (rs.next()) {
					bans.add(SoftBanRecord(rs.getString("ip_address"), rs.getLong("expires_at")))
				}
			}
		}
		return bans
	}

	fun getLinkedUUIDs(accountId: UUID): List<String> {
		val uuids = mutableListOf<String>()
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT minecraft_uuid FROM account_links WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				val rs = stmt.executeQuery()
				while (rs.next()) {
					uuids.add(rs.getString("minecraft_uuid"))
				}
			}
		}
		return uuids
	}

	fun getActiveSessions(): List<SessionRecord> {
		val sessions = mutableListOf<SessionRecord>()
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT s.account_id, a.username, s.ip_address, s.expires_at FROM auth_sessions s JOIN accounts a ON s.account_id = a.id WHERE s.expires_at > ?"
			).use { stmt ->
				stmt.setLong(1, System.currentTimeMillis())
				val rs = stmt.executeQuery()
				while (rs.next()) {
					sessions.add(
						SessionRecord(
							accountId = rs.getString("account_id"),
							username = rs.getString("username"),
							ipAddress = rs.getString("ip_address"),
							expiresAt = rs.getLong("expires_at"),
						)
					)
				}
			}
		}
		return sessions
	}

	data class SessionRecord(val accountId: String, val username: String, val ipAddress: String, val expiresAt: Long)

	fun close() {
		if (!dataSource.isClosed) {
			dataSource.close()
		}
	}
}

data class InviteCodeRecord(
	val code: String,
	val createdBy: String,
	val createdAt: Long,
	val maxUses: Int,
	val currentUses: Int,
)
