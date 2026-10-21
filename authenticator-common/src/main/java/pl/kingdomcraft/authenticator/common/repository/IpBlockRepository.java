package pl.kingdomcraft.authenticator.common.repository;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Sql;

/** {@code auth_ip_blocks}: addresses locked out after too many wrong passwords. */
public final class IpBlockRepository {

	private final Database database;

	public IpBlockRepository(Database database) {
		this.database = database;
	}

	/** Blocks an address until the given time; blocking it again never shortens an existing block. */
	public void block(InetAddress ip, Instant until, String reason) throws SQLException {
		this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO auth_ip_blocks (ip, blocked_until, reason) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE blocked_until = GREATEST(blocked_until, VALUES(blocked_until)), reason = VALUES(reason)")) {
				Sql.setIp(statement, 1, ip);
				Sql.setInstant(statement, 2, until);
				statement.setString(3, reason);
				statement.executeUpdate();
			}

			return null;
		});
	}

	/** @return until when the address is blocked, or empty if it is not */
	public Optional<Instant> blockedUntil(InetAddress ip, Instant now) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("SELECT blocked_until FROM auth_ip_blocks WHERE ip = ? AND blocked_until > ?")) {
				Sql.setIp(statement, 1, ip);
				Sql.setInstant(statement, 2, now);

				try (ResultSet result = statement.executeQuery()) {
					return result.next() ? Optional.of(Sql.instant(result, "blocked_until")) : Optional.empty();
				}
			}
		});
	}

	public boolean unblock(InetAddress ip) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_ip_blocks WHERE ip = ?")) {
				Sql.setIp(statement, 1, ip);

				return statement.executeUpdate() > 0;
			}
		});
	}

	public int deleteExpired(Instant now) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_ip_blocks WHERE blocked_until <= ?")) {
				Sql.setInstant(statement, 1, now);

				return statement.executeUpdate();
			}
		});
	}
}
