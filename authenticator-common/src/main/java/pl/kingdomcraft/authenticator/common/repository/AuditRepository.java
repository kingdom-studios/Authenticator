package pl.kingdomcraft.authenticator.common.repository;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Sql;

/** {@code auth_audit}: logins, wrong passwords and admin actions. */
public final class AuditRepository {

	/** What happened. */
	public enum Event {
		LOGIN_SUCCESS,
		LOGIN_FAILED,
		LOGIN_BLOCKED,
		REGISTER,
		PASSWORD_CHANGED,
		UNREGISTER,
		PREMIUM_ENABLED,
		CRACKED_ENABLED,
		ACCOUNT_RENAMED,
		PASSWORD_UPGRADED,
		IP_BLOCKED,
		IMPORT,
		FORCED_LOGIN,
		FORCED_LOGOUT
	}

	private static final int MAX_DETAILS = 255;

	private final Database database;

	public AuditRepository(Database database) {
		this.database = database;
	}

	/** @param actor who did it when it was not the player: an admin's name or CONSOLE; may be null */
	public void log(Event event, UUID uuid, String username, InetAddress ip, String actor, String node, String details) throws SQLException {
		this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_audit (event, uuid, username, ip, actor, node, details) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
				statement.setString(1, event.name());
				Sql.setUuid(statement, 2, uuid);
				Sql.setString(statement, 3, username);
				Sql.setIp(statement, 4, ip);
				Sql.setString(statement, 5, actor);
				Sql.setString(statement, 6, node);
				Sql.setString(statement, 7, details == null ? null : details.length() > MAX_DETAILS ? details.substring(0, MAX_DETAILS) : details);
				statement.executeUpdate();
			}

			return null;
		});
	}

	/** Removes entries older than the retention period. */
	public int deleteOlderThan(Instant before) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_audit WHERE created_at < ?")) {
				Sql.setInstant(statement, 1, before);

				return statement.executeUpdate();
			}
		});
	}
}
