package tech.lenooby09.crackAuth.storage

import tech.lenooby09.crackAuth.auth.AuthAccount
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

class DatabaseManager(dbPath: Path) {

	private val connection: Connection

	init {
		Class.forName("org.sqlite.JDBC")
		connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
		connection.autoCommit = true
		createTables()
	}

	private fun createTables() {
		connection.createStatement().use { stmt ->
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
		}
	}

	// --- Account operations ---

	fun saveAccount(account: AuthAccount) {
		connection.prepareStatement(
			"INSERT INTO accounts (id, username, password_hash, registered_at) VALUES (?, ?, ?, ?)"
		).use { stmt ->
			stmt.setString(1, account.id.toString())
			stmt.setString(2, account.username)
			stmt.setString(3, account.passwordHash)
			stmt.setLong(4, account.registeredAt)
			stmt.executeUpdate()
		}
	}

	fun getAccountByUsername(username: String): AuthAccount? {
		connection.prepareStatement(
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
		return null
	}

	fun getAccountByMinecraftUUID(minecraftUuid: UUID): AuthAccount? {
		connection.prepareStatement(
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
		return null
	}

	fun linkMinecraftAccount(minecraftUuid: UUID, accountId: UUID) {
		connection.prepareStatement(
			"INSERT OR REPLACE INTO account_links (minecraft_uuid, account_id) VALUES (?, ?)"
		).use { stmt ->
			stmt.setString(1, minecraftUuid.toString())
			stmt.setString(2, accountId.toString())
			stmt.executeUpdate()
		}
	}

	// --- Invite code operations ---

	fun saveInviteCode(code: String, createdBy: String, createdAt: Long, maxUses: Int) {
		connection.prepareStatement(
			"INSERT INTO invite_codes (code, created_by, created_at, max_uses, current_uses) VALUES (?, ?, ?, ?, 0)"
		).use { stmt ->
			stmt.setString(1, code)
			stmt.setString(2, createdBy)
			stmt.setLong(3, createdAt)
			stmt.setInt(4, maxUses)
			stmt.executeUpdate()
		}
	}

	fun getInviteCode(code: String): InviteCodeRecord? {
		connection.prepareStatement(
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
		return null
	}

	fun useInviteCode(code: String, usedBy: String) {
		connection.prepareStatement(
			"UPDATE invite_codes SET current_uses = current_uses + 1 WHERE code = ?"
		).use { stmt ->
			stmt.setString(1, code)
			stmt.executeUpdate()
		}
		connection.prepareStatement(
			"INSERT INTO invite_code_uses (code, used_by, used_at) VALUES (?, ?, ?)"
		).use { stmt ->
			stmt.setString(1, code)
			stmt.setString(2, usedBy)
			stmt.setLong(3, System.currentTimeMillis())
			stmt.executeUpdate()
		}
	}

	fun getActiveInviteCodes(): List<InviteCodeRecord> {
		val codes = mutableListOf<InviteCodeRecord>()
		connection.createStatement().use { stmt ->
			val rs = stmt.executeQuery(
				"SELECT code, created_by, created_at, max_uses, current_uses FROM invite_codes WHERE current_uses < max_uses"
			)
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
		return codes
	}

	fun revokeInviteCode(code: String): Boolean {
		connection.prepareStatement(
			"UPDATE invite_codes SET max_uses = 0 WHERE code = ?"
		).use { stmt ->
			stmt.setString(1, code)
			return stmt.executeUpdate() > 0
		}
	}

	fun savePlayerData(accountId: UUID, inventoryData: ByteArray?, enderChestData: ByteArray?) {
		connection.prepareStatement(
			"INSERT OR REPLACE INTO player_data (account_id, inventory_data, ender_chest_data, updated_at) VALUES (?, ?, ?, ?)"
		).use { stmt ->
			stmt.setString(1, accountId.toString())
			if (inventoryData != null) stmt.setBytes(2, inventoryData) else stmt.setNull(2, java.sql.Types.BLOB)
			if (enderChestData != null) stmt.setBytes(3, enderChestData) else stmt.setNull(3, java.sql.Types.BLOB)
			stmt.setLong(4, System.currentTimeMillis())
			stmt.executeUpdate()
		}
	}

	fun loadPlayerData(accountId: UUID): Pair<ByteArray?, ByteArray?>? {
		connection.prepareStatement(
			"SELECT inventory_data, ender_chest_data FROM player_data WHERE account_id = ?"
		).use { stmt ->
			stmt.setString(1, accountId.toString())
			val rs = stmt.executeQuery()
			if (rs.next()) {
				return Pair(rs.getBytes("inventory_data"), rs.getBytes("ender_chest_data"))
			}
		}
		return null
	}

	fun deleteAccountByUsername(username: String): Boolean {
		val account = getAccountByUsername(username) ?: return false
		val accountId = account.id.toString()

		connection.prepareStatement("DELETE FROM player_data WHERE account_id = ?").use { stmt ->
			stmt.setString(1, accountId)
			stmt.executeUpdate()
		}
		connection.prepareStatement("DELETE FROM account_links WHERE account_id = ?").use { stmt ->
			stmt.setString(1, accountId)
			stmt.executeUpdate()
		}
		connection.prepareStatement("DELETE FROM accounts WHERE id = ?").use { stmt ->
			stmt.setString(1, accountId)
			stmt.executeUpdate()
		}
		return true
	}

	fun hasAnyAccounts(): Boolean {
		connection.createStatement().use { stmt ->
			val rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")
			return rs.next() && rs.getInt(1) > 0
		}
	}

	fun close() {
		if (!connection.isClosed) {
			connection.close()
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
