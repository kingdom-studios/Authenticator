package pl.kingdomcraft.authenticator.common.repository;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Sql;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;

/**
 * {@code auth_accounts}. All calls are blocking: run them on the database executor, never on a thread that must not wait.
 * Changes that matter for security take the version the caller read and fail (return false) if the row changed meanwhile.
 */
public final class AccountRepository {

	/** The username or UUID is already taken. */
	public static final class DuplicateAccountException extends SQLException {
		private static final long serialVersionUID = 1L;

		DuplicateAccountException(SQLException cause) {
			super("an account with this name or UUID already exists", cause);
		}
	}

	/**
	 * An account that does not exist yet.
	 *
	 * @param registeredAt when it was registered, as the application's clock says; null lets the database use its own clock (only for imports without a time)
	 * @param lastLoginAt  null for an account that never logged in
	 */
	public record NewAccount(UUID uuid, String username, boolean premium, UUID premiumUuid, String passwordHash, String passwordSalt, HashAlgo hashAlgo, InetAddress registeredIp, Instant registeredAt,
			Instant lastLoginAt, InetAddress lastLoginIp) {

		/** A new account that has never logged in, registered now by the database's clock. */
		public NewAccount(UUID uuid, String username, boolean premium, UUID premiumUuid, String passwordHash, String passwordSalt, HashAlgo hashAlgo, InetAddress registeredIp) {
			this(uuid, username, premium, premiumUuid, passwordHash, passwordSalt, hashAlgo, registeredIp, null, null, null);
		}
	}

	private static final String COLUMNS = "id, uuid, username, premium, premium_uuid, password_hash, password_salt, hash_algo, registered_at, registered_ip, last_login_at, last_login_ip, version";

	private final Database database;

	public AccountRepository(Database database) {
		this.database = database;
	}

	public Optional<Account> findByUsername(String username) throws SQLException {
		return this.findOne("username_lower = ?", statement -> statement.setString(1, Account.key(username)));
	}

	public Optional<Account> findByUuid(UUID uuid) throws SQLException {
		return this.findOne("uuid = ?", statement -> Sql.setUuid(statement, 1, uuid));
	}

	public Optional<Account> findByPremiumUuid(UUID premiumUuid) throws SQLException {
		return this.findOne("premium_uuid = ?", statement -> Sql.setUuid(statement, 1, premiumUuid));
	}

	@FunctionalInterface
	private interface Binder {
		void bind(PreparedStatement statement) throws SQLException;
	}

	private Optional<Account> findOne(String where, Binder binder) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM auth_accounts WHERE " + where)) {
				binder.bind(statement);

				try (ResultSet result = statement.executeQuery()) {
					return result.next() ? Optional.of(read(result)) : Optional.empty();
				}
			}
		});
	}

	/** @throws DuplicateAccountException if the name or UUID is taken */
	public Account insert(NewAccount account) throws SQLException {
		try {
			final long id = this.database.query(connection -> {
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO auth_accounts (uuid, username, premium, premium_uuid, password_hash, password_salt, hash_algo, registered_ip, registered_at, last_login_at, last_login_ip) "
								+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP(3)), ?, ?)",
						Statement.RETURN_GENERATED_KEYS)) {
					Sql.setUuid(statement, 1, account.uuid());
					statement.setString(2, account.username());
					statement.setBoolean(3, account.premium());
					Sql.setUuid(statement, 4, account.premiumUuid());
					Sql.setString(statement, 5, account.passwordHash());
					Sql.setString(statement, 6, account.passwordSalt());
					Sql.setString(statement, 7, account.hashAlgo() == null ? null : account.hashAlgo().name());
					Sql.setIp(statement, 8, account.registeredIp());
					Sql.setInstant(statement, 9, account.registeredAt());
					Sql.setInstant(statement, 10, account.lastLoginAt());
					Sql.setIp(statement, 11, account.lastLoginIp());
					statement.executeUpdate();

					try (ResultSet keys = statement.getGeneratedKeys()) {
						keys.next();

						return keys.getLong(1);
					}
				}
			});

			return this.findById(id).orElseThrow(() -> new SQLException("the account vanished right after it was created"));

		} catch (final SQLIntegrityConstraintViolationException ex) {
			throw new DuplicateAccountException(ex);
		}
	}

	/**
	 * Inserts many accounts in one transaction, silently skipping those whose name or UUID is taken (which is why the caller must have checked the
	 * other constraints). For imports, where one batch is much faster than one statement and one read-back per account.
	 *
	 * @return how many were inserted; the rest were duplicates
	 */
	public int insertIgnoringDuplicates(java.util.List<NewAccount> batch) throws SQLException {
		return this.database.transaction(connection -> {
			int inserted = 0;

			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT IGNORE INTO auth_accounts (uuid, username, premium, premium_uuid, password_hash, password_salt, hash_algo, registered_ip, registered_at, last_login_at, last_login_ip) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP(3)), ?, ?)")) {
				for (final NewAccount account : batch) {
					Sql.setUuid(statement, 1, account.uuid());
					statement.setString(2, account.username());
					statement.setBoolean(3, account.premium());
					Sql.setUuid(statement, 4, account.premiumUuid());
					Sql.setString(statement, 5, account.passwordHash());
					Sql.setString(statement, 6, account.passwordSalt());
					Sql.setString(statement, 7, account.hashAlgo() == null ? null : account.hashAlgo().name());
					Sql.setIp(statement, 8, account.registeredIp());
					Sql.setInstant(statement, 9, account.registeredAt());
					Sql.setInstant(statement, 10, account.lastLoginAt());
					Sql.setIp(statement, 11, account.lastLoginIp());

					inserted += statement.executeUpdate();
				}
			}

			return inserted;
		});
	}

	public Optional<Account> findById(long id) throws SQLException {
		return this.findOne("id = ?", statement -> statement.setLong(1, id));
	}

	/** Replaces the password; the salt is cleared, because new hashes never use one. @return false if the account changed since {@code expectedVersion} */
	public boolean updatePassword(long id, long expectedVersion, String hash, HashAlgo algo) throws SQLException {
		return this.update("UPDATE auth_accounts SET password_hash = ?, password_salt = NULL, hash_algo = ?, version = version + 1 WHERE id = ? AND version = ?", statement -> {
			statement.setString(1, hash);
			statement.setString(2, algo.name());
			statement.setLong(3, id);
			statement.setLong(4, expectedVersion);
		});
	}

	/** Makes the account a premium one. The UUID the player has on the servers stays as it was. */
	public boolean setPremium(long id, long expectedVersion, UUID premiumUuid) throws SQLException {
		return this.update("UPDATE auth_accounts SET premium = 1, premium_uuid = ?, version = version + 1 WHERE id = ? AND version = ?", statement -> {
			Sql.setUuid(statement, 1, premiumUuid);
			statement.setLong(2, id);
			statement.setLong(3, expectedVersion);
		});
	}

	/** Makes the account a non-premium one with the given password. */
	public boolean setCracked(long id, long expectedVersion, String hash, HashAlgo algo) throws SQLException {
		return this.update("UPDATE auth_accounts SET premium = 0, premium_uuid = NULL, password_hash = ?, password_salt = NULL, hash_algo = ?, version = version + 1 WHERE id = ? AND version = ?",
				statement -> {
					statement.setString(1, hash);
					statement.setString(2, algo.name());
					statement.setLong(3, id);
					statement.setLong(4, expectedVersion);
				});
	}

	/** For a premium player who changed their name. @throws DuplicateAccountException if the new name is taken */
	public boolean rename(long id, long expectedVersion, String newUsername) throws SQLException {
		try {
			return this.update("UPDATE auth_accounts SET username = ?, version = version + 1 WHERE id = ? AND version = ?", statement -> {
				statement.setString(1, newUsername);
				statement.setLong(2, id);
				statement.setLong(3, expectedVersion);
			});

		} catch (final SQLIntegrityConstraintViolationException ex) {
			throw new DuplicateAccountException(ex);
		}
	}

	/** Records a login. Does not change the version: it is not a change to the account. */
	public void recordLogin(long id, InetAddress ip, Instant at) throws SQLException {
		this.update("UPDATE auth_accounts SET last_login_at = ?, last_login_ip = ? WHERE id = ?", statement -> {
			Sql.setInstant(statement, 1, at);
			Sql.setIp(statement, 2, ip);
			statement.setLong(3, id);
		});
	}

	public boolean delete(long id) throws SQLException {
		return this.update("DELETE FROM auth_accounts WHERE id = ?", statement -> statement.setLong(1, id));
	}

	/** Accounts registered from this address since the given time. */
	public int countRegisteredFrom(InetAddress ip, Instant since) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM auth_accounts WHERE registered_ip = ? AND registered_at >= ?")) {
				Sql.setIp(statement, 1, ip);
				Sql.setInstant(statement, 2, since);

				try (ResultSet result = statement.executeQuery()) {
					result.next();

					return result.getInt(1);
				}
			}
		});
	}

	/** The current version of each of these accounts; an account that does not exist is missing from the map. */
	public Map<UUID, Long> versions(Collection<UUID> uuids) throws SQLException {
		if (uuids.isEmpty())
			return Map.of();

		return this.database.query(connection -> {
			final String placeholders = String.join(",", java.util.Collections.nCopies(uuids.size(), "?"));
			final Map<UUID, Long> versions = new HashMap<>();

			try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, version FROM auth_accounts WHERE uuid IN (" + placeholders + ")")) {
				int index = 1;

				for (final UUID uuid : uuids)
					Sql.setUuid(statement, index++, uuid);

				try (ResultSet result = statement.executeQuery()) {
					while (result.next())
						versions.put(Sql.uuid(result, "uuid"), result.getLong("version"));
				}
			}

			return versions;
		});
	}

	public long count() throws SQLException {
		return this.database.query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM auth_accounts")) {
				result.next();

				return result.getLong(1);
			}
		});
	}

	private boolean update(String sql, Binder binder) throws SQLException {
		return this.database.query((Connection connection) -> {
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				binder.bind(statement);

				return statement.executeUpdate() == 1;
			}
		});
	}

	private static Account read(ResultSet result) throws SQLException {
		return new Account(result.getLong("id"), Sql.uuid(result, "uuid"), result.getString("username"), result.getBoolean("premium"), Sql.uuid(result, "premium_uuid"),
				result.getString("password_hash"), result.getString("password_salt"), HashAlgo.parse(result.getString("hash_algo")), Sql.instant(result, "registered_at"),
				Sql.ip(result, "registered_ip"), Sql.instant(result, "last_login_at"), Sql.ip(result, "last_login_ip"), result.getLong("version"));
	}
}
