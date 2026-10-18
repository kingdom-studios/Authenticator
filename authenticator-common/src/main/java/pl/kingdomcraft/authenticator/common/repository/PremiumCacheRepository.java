package pl.kingdomcraft.authenticator.common.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Sql;

/** {@code auth_premium_cache}: what Mojang said about a name, so an outage of the lookup APIs does not stop new players. */
public final class PremiumCacheRepository {

	/** @param premiumUuid null means the name is not an original account */
	public record Entry(String username, UUID premiumUuid, String source, Instant checkedAt) {
	}

	private final Database database;

	public PremiumCacheRepository(Database database) {
		this.database = database;
	}

	public Optional<Entry> find(String username) throws SQLException {
		final String key = Account.key(username);

		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("SELECT username_lower, premium_uuid, source, checked_at FROM auth_premium_cache WHERE username_lower = ?")) {
				statement.setString(1, key);

				try (ResultSet result = statement.executeQuery()) {
					return result.next() ? Optional.of(new Entry(result.getString("username_lower"), Sql.uuid(result, "premium_uuid"), result.getString("source"), Sql.instant(result, "checked_at")))
							: Optional.empty();
				}
			}
		});
	}

	public void save(String username, UUID premiumUuid, String source, Instant checkedAt) throws SQLException {
		final String key = Account.key(username);

		this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_premium_cache (username_lower, premium_uuid, source, checked_at) VALUES (?, ?, ?, ?) "
					+ "ON DUPLICATE KEY UPDATE premium_uuid = VALUES(premium_uuid), source = VALUES(source), checked_at = VALUES(checked_at)")) {
				statement.setString(1, key);
				Sql.setUuid(statement, 2, premiumUuid);
				statement.setString(3, source);
				Sql.setInstant(statement, 4, checkedAt);
				statement.executeUpdate();
			}

			return null;
		});
	}

	public int deleteOlderThan(Instant before) throws SQLException {
		return this.database.query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_premium_cache WHERE checked_at < ?")) {
				Sql.setInstant(statement, 1, before);

				return statement.executeUpdate();
			}
		});
	}
}
