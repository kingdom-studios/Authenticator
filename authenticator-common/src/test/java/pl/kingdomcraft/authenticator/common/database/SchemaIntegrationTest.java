package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

/** The constraints the schema promises, checked against a real MariaDB. */
class SchemaIntegrationTest {

	private TestMariaDb.Schema schema;

	@BeforeEach
	void setUp() throws Exception {
		this.schema = TestMariaDb.createSchema(true);
		new Migrator(this.schema.database(), Logger.getLogger("test")).migrate();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.schema != null)
			this.schema.close();
	}

	private void execute(String sql) throws SQLException {
		this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}

			return null;
		});
	}

	private long count(String sql) throws SQLException {
		return this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
				result.next();

				return result.getLong(1);
			}
		});
	}

	private long account(String username) throws SQLException {
		return this.account(username, UUID.randomUUID());
	}

	private long account(String username, UUID uuid) throws SQLException {
		return this.schema.database().query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_accounts (uuid, username) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
				Sql.setUuid(statement, 1, uuid);
				statement.setString(2, username);
				statement.executeUpdate();

				try (ResultSet keys = statement.getGeneratedKeys()) {
					keys.next();

					return keys.getLong(1);
				}
			}
		});
	}

	private static void assertConstraint(String constraint, ThrowingRunnable action) {
		final SQLException ex = assertThrows(SQLException.class, action::run);

		assertTrue(ex.getMessage().contains(constraint), "expected " + constraint + " but got: " + ex.getMessage());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws SQLException;
	}

	@Test
	void usernamesAreUniqueIgnoringCase() throws Exception {
		this.account("Steve");

		assertConstraint("uq_accounts_username", () -> this.account("STEVE"));
		assertEquals("steve", this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT username_lower FROM auth_accounts")) {
				result.next();

				return result.getString(1);
			}
		}));
	}

	@Test
	void uuidsAreUnique() throws Exception {
		final UUID uuid = UUID.randomUUID();
		this.account("Alex", uuid);

		assertConstraint("uq_accounts_uuid", () -> this.account("Other", uuid));
	}

	@Test
	void premiumUuidIsUniqueButSeveralAccountsMayHaveNone() throws Exception {
		this.account("A");
		this.account("B");

		final UUID mojang = UUID.randomUUID();
		this.execute("UPDATE auth_accounts SET premium = 1, premium_uuid = '" + mojang + "' WHERE username = 'A'");

		assertConstraint("uq_accounts_premium_uuid", () -> this.execute("UPDATE auth_accounts SET premium = 1, premium_uuid = '" + mojang + "' WHERE username = 'B'"));
	}

	@Test
	void aPremiumAccountMustHaveAMojangUuid() throws Exception {
		this.account("A");

		assertConstraint("ck_accounts_premium", () -> this.execute("UPDATE auth_accounts SET premium = 1 WHERE username = 'A'"));
	}

	@Test
	void aPasswordHashAndItsAlgorithmGoTogether() throws Exception {
		this.account("A");

		assertConstraint("ck_accounts_hash", () -> this.execute("UPDATE auth_accounts SET password_hash = 'x' WHERE username = 'A'"));
		assertConstraint("ck_accounts_hash", () -> this.execute("UPDATE auth_accounts SET hash_algo = 'BCRYPT' WHERE username = 'A'"));

		this.execute("UPDATE auth_accounts SET password_hash = 'x', hash_algo = 'BCRYPT' WHERE username = 'A'");
	}

	@Test
	void anAuthenticatedPlayerNeedsAnAccountAndAMethod() throws Exception {
		final long account = this.account("A");
		final UUID uuid = UUID.randomUUID();

		assertConstraint("ck_presence_authenticated", () -> this.execute("INSERT INTO auth_presence (uuid, state, proxy_id) VALUES ('" + uuid + "', 'AUTHENTICATED', 'velocity-1')"));
		assertConstraint("ck_presence_authenticated", () -> this.execute("INSERT INTO auth_presence (uuid, account_id, state, proxy_id) VALUES ('" + uuid + "', " + account + ", 'AUTHENTICATED', 'velocity-1')"));

		this.execute("INSERT INTO auth_presence (uuid, account_id, state, method, proxy_id) VALUES ('" + uuid + "', " + account + ", 'AUTHENTICATED', 'PASSWORD', 'velocity-1')");

		// A player who has not registered yet has no account.
		this.execute("INSERT INTO auth_presence (uuid, state, proxy_id) VALUES ('" + UUID.randomUUID() + "', 'PENDING_REGISTER', 'velocity-1')");
	}

	@Test
	void removingAnAccountRemovesItsSessionAndPresence() throws Exception {
		final long account = this.account("A");
		final UUID uuid = UUID.randomUUID();

		this.execute("INSERT INTO auth_sessions (account_id, ip, expires_at) VALUES (" + account + ", '::ffff:1.2.3.4', '2030-01-01 00:00:00')");
		this.execute("INSERT INTO auth_presence (uuid, account_id, state, method, proxy_id) VALUES ('" + uuid + "', " + account + ", 'AUTHENTICATED', 'SESSION', 'velocity-1')");

		this.execute("DELETE FROM auth_accounts WHERE id = " + account);

		assertEquals(0, this.count("SELECT COUNT(*) FROM auth_sessions"));
		assertEquals(0, this.count("SELECT COUNT(*) FROM auth_presence"));
	}

	@Test
	void theAuditTrailOutlivesTheAccount() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final long account = this.account("A", uuid);

		this.execute("INSERT INTO auth_audit (event, uuid, username) VALUES ('REGISTER', '" + uuid + "', 'A')");
		this.execute("DELETE FROM auth_accounts WHERE id = " + account);

		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_audit"));
	}

	@Test
	void ipAddressesRoundTripAndCompareInBothFamilies() throws Exception {
		final InetAddress v4 = InetAddress.getByName("203.0.113.7");
		final InetAddress v6 = InetAddress.getByName("2001:db8::7");

		this.schema.database().transaction(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_ip_blocks (ip, blocked_until, reason) VALUES (?, '2030-01-01 00:00:00', 'test')")) {
				for (final InetAddress address : new InetAddress[] { v4, v6 }) {
					Sql.setIp(statement, 1, address);
					statement.executeUpdate();
				}
			}

			return null;
		});

		for (final InetAddress address : new InetAddress[] { v4, v6 })
			assertEquals(address, this.schema.database().query(connection -> {
				try (PreparedStatement statement = connection.prepareStatement("SELECT ip FROM auth_ip_blocks WHERE ip = ?")) {
					Sql.setIp(statement, 1, address);

					try (ResultSet result = statement.executeQuery()) {
						assertTrue(result.next(), "no row found by " + address);

						return Sql.ip(result, "ip");
					}
				}
			}));
	}

	@Test
	void nullAddressesAndUuidsAreStoredAsNull() throws Exception {
		this.schema.database().transaction(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_audit (event, uuid, ip) VALUES ('X', ?, ?)")) {
				Sql.setUuid(statement, 1, null);
				Sql.setIp(statement, 2, null);
				statement.executeUpdate();
			}

			return null;
		});

		this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT uuid, ip FROM auth_audit")) {
				result.next();

				assertNull(Sql.uuid(result, "uuid"));
				assertNull(Sql.ip(result, "ip"));
			}

			return null;
		});
	}

	/** The driver is told to use UTC; a JVM in another time zone must still store and read the same instants. */
	@Test
	void instantsSurviveAJvmInAnotherTimeZone() throws Exception {
		final TimeZone original = TimeZone.getDefault();

		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));

			final Instant written = Instant.parse("2031-07-01T12:34:56.789Z");
			final long account = this.account("A");

			this.schema.database().transaction(connection -> {
				try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_sessions (account_id, ip, expires_at) VALUES (?, '::1', ?)")) {
					statement.setLong(1, account);
					Sql.setInstant(statement, 2, written);
					statement.executeUpdate();
				}

				return null;
			});

			assertEquals(written, this.schema.database().query(connection -> {
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT expires_at FROM auth_sessions")) {
					result.next();

					return Sql.instant(result, "expires_at");
				}
			}));

			// Reading back what was written proves nothing if both directions share the same mistake: look at what the server holds.
			assertEquals("2031-07-01 12:34:56.789", this.schema.database().query(connection -> {
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT CAST(expires_at AS CHAR) FROM auth_sessions")) {
					result.next();

					return result.getString(1);
				}
			}));

			// The same for a TIMESTAMP column, which the server converts through the session time zone (UTC).
			this.schema.database().transaction(connection -> {
				try (PreparedStatement statement = connection.prepareStatement("UPDATE auth_accounts SET last_login_at = ? WHERE id = ?")) {
					Sql.setInstant(statement, 1, written);
					statement.setLong(2, account);
					statement.executeUpdate();
				}

				return null;
			});

			assertEquals("2031-07-01 12:34:56.789", this.schema.database().query(connection -> {
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT CAST(last_login_at AS CHAR) FROM auth_accounts")) {
					result.next();

					return result.getString(1);
				}
			}));

			assertEquals(written, this.schema.database().query(connection -> {
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT last_login_at FROM auth_accounts")) {
					result.next();

					return Sql.instant(result, "last_login_at");
				}
			}));

			// A DEFAULT CURRENT_TIMESTAMP filled in by the server must also be "now" in UTC.
			final Instant registered = this.schema.database().query(connection -> {
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT registered_at FROM auth_accounts")) {
					result.next();

					return Sql.instant(result, "registered_at");
				}
			});

			assertTrue(Duration.between(registered, Instant.now()).abs().compareTo(Duration.ofSeconds(10)) < 0, "registered_at " + registered + " is not close to now");

		} finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void instantsKeepMillisecondPrecision() throws Exception {
		final Instant written = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		this.schema.database().transaction(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_ip_blocks (ip, blocked_until, reason) VALUES ('::1', ?, 'p')")) {
				Sql.setInstant(statement, 1, written);
				statement.executeUpdate();
			}

			return null;
		});

		assertEquals(written, this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT blocked_until FROM auth_ip_blocks")) {
				result.next();

				return Sql.instant(result, "blocked_until");
			}
		}));
	}
}
