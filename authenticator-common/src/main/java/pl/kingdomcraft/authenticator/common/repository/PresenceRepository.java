package pl.kingdomcraft.authenticator.common.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Sql;

/**
 * {@code auth_presence}: who is online and how far they got. The proxy writes it before it announces a change; a backend server reads
 * it when a player joins and whenever no message has told it better.
 */
public final class PresenceRepository {

	/**
	 * @param accountId null until a new player has registered
	 * @param method    null unless the player is authenticated
	 * @param epoch     counts up with every change of this player's row
	 */
	public record Presence(UUID uuid, Long accountId, AuthState state, AuthMethod method, long epoch, String proxyId, Instant updatedAt) {
	}

	private final Database database;

	public PresenceRepository(Database database) {
		this.database = database;
	}

	/** Writes the player's state and counts the epoch up. @return the new epoch */
	public long save(UUID uuid, Long accountId, AuthState state, AuthMethod method, String proxyId) throws SQLException {
		return this.database.transaction(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_presence (uuid, account_id, state, method, epoch, proxy_id) VALUES (?, ?, ?, ?, 1, ?) "
					+ "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id), state = VALUES(state), method = VALUES(method), epoch = epoch + 1, proxy_id = VALUES(proxy_id)")) {
				Sql.setUuid(statement, 1, uuid);

				if (accountId == null)
					statement.setNull(2, java.sql.Types.BIGINT);
				else
					statement.setLong(2, accountId);

				statement.setString(3, state.name());
				Sql.setString(statement, 4, method == null ? null : method.name());
				statement.setString(5, proxyId);
				statement.executeUpdate();
			}

			try (PreparedStatement statement = connection.prepareStatement("SELECT epoch FROM auth_presence WHERE uuid = ?")) {
				Sql.setUuid(statement, 1, uuid);

				try (ResultSet result = statement.executeQuery()) {
					result.next();

					return result.getLong(1);
				}
			}
		});
	}

	public Optional<Presence> find(UUID uuid) throws SQLException {
		return Optional.ofNullable(this.findAll(Collections.singleton(uuid)).get(uuid));
	}

	public Map<UUID, Presence> findAll(Collection<UUID> uuids) throws SQLException {
		if (uuids.isEmpty())
			return Map.of();

		return this.database.query(connection -> {
			final String placeholders = String.join(",", Collections.nCopies(uuids.size(), "?"));
			final Map<UUID, Presence> found = new HashMap<>();

			try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, account_id, state, method, epoch, proxy_id, updated_at FROM auth_presence WHERE uuid IN (" + placeholders + ")")) {
				int index = 1;

				for (final UUID uuid : uuids)
					Sql.setUuid(statement, index++, uuid);

				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						final long accountId = result.getLong("account_id");
						final Long account = result.wasNull() ? null : accountId;
						final String method = result.getString("method");

						found.put(Sql.uuid(result, "uuid"),
								new Presence(Sql.uuid(result, "uuid"), account, AuthState.valueOf(result.getString("state")), method == null ? null : AuthMethod.valueOf(method),
										result.getLong("epoch"), result.getString("proxy_id"), Sql.instant(result, "updated_at")));
					}
				}
			}

			return found;
		});
	}

	public void delete(UUID uuid) throws SQLException {
		this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_presence WHERE uuid = ?")) {
				Sql.setUuid(statement, 1, uuid);
				statement.executeUpdate();
			}

			return null;
		});
	}

	/**
	 * Removes the player's row only if nobody has changed it since the given epoch. A player who reconnects quickly gets a new row
	 * before the old connection's disconnect is handled; this keeps that late delete from removing the new one.
	 */
	public boolean deleteIfUnchanged(UUID uuid, long epoch) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_presence WHERE uuid = ? AND epoch = ?")) {
				Sql.setUuid(statement, 1, uuid);
				statement.setLong(2, epoch);

				return statement.executeUpdate() > 0;
			}
		});
	}

	/** Removes every row a proxy owns, for the rows it left behind when it last stopped without cleaning up. */
	public int deleteOwnedBy(String proxyId) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_presence WHERE proxy_id = ?")) {
				statement.setString(1, proxyId);

				return statement.executeUpdate();
			}
		});
	}
}
