package pl.kingdomcraft.authenticator.common.repository;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Sql;

/** {@code auth_sessions}: lets a non-premium player come back from the same address without typing the password again. */
public final class SessionRepository {

	public record StoredSession(long accountId, InetAddress ip, Instant expiresAt) {
	}

	private final Database database;

	public SessionRepository(Database database) {
		this.database = database;
	}

	/** One session per account: a new login from another address replaces the old one. */
	public void save(long accountId, InetAddress ip, Instant expiresAt) throws SQLException {
		this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO auth_sessions (account_id, ip, expires_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE ip = VALUES(ip), expires_at = VALUES(expires_at), created_at = CURRENT_TIMESTAMP(3)")) {
				statement.setLong(1, accountId);
				Sql.setIp(statement, 2, ip);
				Sql.setInstant(statement, 3, expiresAt);
				statement.executeUpdate();
			}

			return null;
		});
	}

	/** The session, if it is for this address and has not expired. */
	public Optional<StoredSession> findValid(long accountId, InetAddress ip, Instant now) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("SELECT account_id, ip, expires_at FROM auth_sessions WHERE account_id = ? AND ip = ? AND expires_at > ?")) {
				statement.setLong(1, accountId);
				Sql.setIp(statement, 2, ip);
				Sql.setInstant(statement, 3, now);

				try (ResultSet result = statement.executeQuery()) {
					return result.next() ? Optional.of(new StoredSession(result.getLong("account_id"), Sql.ip(result, "ip"), Sql.instant(result, "expires_at"))) : Optional.empty();
				}
			}
		});
	}

	public void delete(long accountId) throws SQLException {
		this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_sessions WHERE account_id = ?")) {
				statement.setLong(1, accountId);
				statement.executeUpdate();
			}

			return null;
		});
	}

	public int deleteExpired(Instant now) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_sessions WHERE expires_at <= ?")) {
				Sql.setInstant(statement, 1, now);

				return statement.executeUpdate();
			}
		});
	}
}
