package tech.lenooby09.offlineAuth.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import tech.lenooby09.offlineAuth.auth.AuthAccount
import java.nio.file.Path
import java.sql.Connection
import java.util.*

class DatabaseManager(dbPath: Path) {

	private val dataSource: HikariDataSource

	init {
		Class.forName("org.sqlite.JDBC")
		val hikariConfig = HikariConfig().apply {
			jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
			maximumPoolSize = 10
			minimumIdle = 2
			connectionTimeout = 5000
			idleTimeout = 60000
			maxLifetime = 300000
			// SQLite requires serialized access for writes; WAL mode helps with concurrent reads
			addDataSourceProperty("journal_mode", "WAL")
		}
		dataSource = HikariDataSource(hikariConfig)

		// Enable WAL mode for better concurrent access
		connection().use { conn ->
			conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
		}

		createTables()
		migrateSchema()
	}

	private fun connection(): Connection = dataSource.connection

	private fun createTables() {
		connection().use { conn ->
			conn.createStatement().use { stmt ->
				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    registered_at INTEGER NOT NULL
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS invite_codes (
                    code TEXT PRIMARY KEY,
                    created_by TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
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
                    used_at INTEGER NOT NULL,
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
                    inventory_data BLOB,
                    ender_chest_data BLOB,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS spawn_positions (
                    minecraft_uuid TEXT PRIMARY KEY,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    z REAL NOT NULL,
                    dimension TEXT NOT NULL DEFAULT 'minecraft:overworld'
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS account_positions (
                    account_id TEXT PRIMARY KEY,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    z REAL NOT NULL,
                    yaw REAL NOT NULL DEFAULT 0,
                    pitch REAL NOT NULL DEFAULT 0,
                    dimension TEXT NOT NULL DEFAULT 'minecraft:overworld',
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)

				stmt.executeUpdate(
					"""
                CREATE TABLE IF NOT EXISTS auth_sessions (
                    account_id TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY (account_id, ip_address),
                    FOREIGN KEY (account_id) REFERENCES accounts(id)
                )
                """.trimIndent()
				)
			}
		}
	}

	private fun migrateSchema() {
		connection().use { conn ->
			// Migrate account_positions
			val apColumns = mutableSetOf<String>()
			conn.createStatement().use { stmt ->
				val rs = stmt.executeQuery("PRAGMA table_info(account_positions)")
				while (rs.next()) {
					apColumns.add(rs.getString("name"))
				}
			}
			if (apColumns.isNotEmpty()) {
				if ("yaw" !in apColumns) {
					conn.createStatement().use { stmt ->
						stmt.executeUpdate("ALTER TABLE account_positions ADD COLUMN yaw REAL NOT NULL DEFAULT 0")
					}
				}
				if ("pitch" !in apColumns) {
					conn.createStatement().use { stmt ->
						stmt.executeUpdate("ALTER TABLE account_positions ADD COLUMN pitch REAL NOT NULL DEFAULT 0")
					}
				}
				if ("dimension" !in apColumns) {
					conn.createStatement().use { stmt ->
						stmt.executeUpdate("ALTER TABLE account_positions ADD COLUMN dimension TEXT NOT NULL DEFAULT 'minecraft:overworld'")
					}
				}
			}

			// Migrate spawn_positions
			val spColumns = mutableSetOf<String>()
			conn.createStatement().use { stmt ->
				val rs = stmt.executeQuery("PRAGMA table_info(spawn_positions)")
				while (rs.next()) {
					spColumns.add(rs.getString("name"))
				}
			}
			if (spColumns.isNotEmpty()) {
				if ("dimension" !in spColumns) {
					conn.createStatement().use { stmt ->
						stmt.executeUpdate("ALTER TABLE spawn_positions ADD COLUMN dimension TEXT NOT NULL DEFAULT 'minecraft:overworld'")
					}
				}
			}
		}
	}

	// --- Account operations ---

	fun saveAccount(account: AuthAccount) {
		connection().use { conn ->
			conn.prepareStatement(
				"INSERT INTO accounts (id, username, password_hash, registered_at) VALUES (?, ?, ?, ?)"
			).use { stmt ->
				stmt.setString(1, account.id.toString())
				stmt.setString(2, account.username)
				stmt.setString(3, account.passwordHash)
				stmt.setLong(4, account.registeredAt)
				stmt.executeUpdate()
			}
		}
	}

	fun getAccountByUsername(username: String): AuthAccount? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT id, username, password_hash, registered_at FROM accounts WHERE LOWER(username) = LOWER(?)"
			).use { stmt ->
				stmt.setString(1, username)
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return AuthAccount(
						id = UUID.fromString(rs.getString("id")),
						username = rs.getString("username"),
						passwordHash = rs.getString("password_hash"),
						registeredAt = rs.getLong("registered_at"),
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
            SELECT a.id, a.username, a.password_hash, a.registered_at
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
					)
				}
			}
		}
		return null
	}

	fun linkMinecraftAccount(minecraftUuid: UUID, accountId: UUID) {
		connection().use { conn ->
			conn.prepareStatement(
				"INSERT OR REPLACE INTO account_links (minecraft_uuid, account_id) VALUES (?, ?)"
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

	fun savePlayerData(accountId: UUID, inventoryData: ByteArray?, enderChestData: ByteArray?) {
		connection().use { conn ->
			conn.prepareStatement(
				"INSERT OR REPLACE INTO player_data (account_id, inventory_data, ender_chest_data, updated_at) VALUES (?, ?, ?, ?)"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				stmt.setBytes(2, inventoryData)
				stmt.setBytes(3, enderChestData)
				stmt.setLong(4, System.currentTimeMillis())
				stmt.executeUpdate()
			}
		}
	}

	fun loadPlayerData(accountId: UUID): Pair<ByteArray?, ByteArray?>? {
		connection().use { conn ->
			conn.prepareStatement(
				"SELECT inventory_data, ender_chest_data FROM player_data WHERE account_id = ?"
			).use { stmt ->
				stmt.setString(1, accountId.toString())
				val rs = stmt.executeQuery()
				if (rs.next()) {
					return Pair(rs.getBytes("inventory_data"), rs.getBytes("ender_chest_data"))
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
				"INSERT OR REPLACE INTO spawn_positions (minecraft_uuid, x, y, z, dimension) VALUES (?, ?, ?, ?, ?)"
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
				"INSERT OR REPLACE INTO account_positions (account_id, x, y, z, yaw, pitch, dimension) VALUES (?, ?, ?, ?, ?, ?, ?)"
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
				"INSERT OR REPLACE INTO auth_sessions (account_id, ip_address, expires_at) VALUES (?, ?, ?)"
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

	// --- Password operations ---

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
