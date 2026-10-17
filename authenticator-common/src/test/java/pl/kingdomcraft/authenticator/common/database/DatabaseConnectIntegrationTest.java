package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

/** Which endpoint is used: the {@code use} zone, then the fallbacks in order. */
class DatabaseConnectIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");

	private TestMariaDb.Server server;
	private TestMariaDb.Schema schema;
	private final List<Database> opened = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		this.server = TestMariaDb.requireServer();
		this.schema = TestMariaDb.createSchema(false);
	}

	@AfterEach
	void tearDown() throws Exception {
		for (final Database database : this.opened)
			database.close();

		if (this.schema != null)
			this.schema.close();
	}

	/** An address nothing listens on, so connecting fails at once. */
	private static DatabaseEndpoint dead(SslMode mode) {
		return new DatabaseEndpoint("127.0.0.1", 1, "nobody", "x", mode, Map.of());
	}

	private DatabaseEndpoint working() {
		return this.server.endpoint(SslMode.DISABLE, Map.of());
	}

	private Database connect(Zone use, List<Zone> fallback, DatabaseEndpoint publicEndpoint, DatabaseEndpoint privateEndpoint) throws SQLException {
		final Map<Zone, DatabaseEndpoint> endpoints = new java.util.EnumMap<>(Zone.class);

		if (publicEndpoint != null)
			endpoints.put(Zone.PUBLIC, publicEndpoint);

		if (privateEndpoint != null)
			endpoints.put(Zone.PRIVATE, privateEndpoint);

		final Database database = Database.connect(new DatabaseConfig(this.schema.name(), new ZonedEndpoints<>(use, fallback, endpoints), 2, 2_000), LOGGER);
		this.opened.add(database);

		return database;
	}

	private static int selectOne(Database database) throws SQLException {
		return database.query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT 1")) {
				result.next();

				return result.getInt(1);
			}
		});
	}

	@Test
	void usesTheConfiguredZone() throws Exception {
		final Database database = this.connect(Zone.PRIVATE, List.of(), dead(SslMode.TRUST), this.working());

		assertEquals(Zone.PRIVATE, database.zone());
		assertEquals(1, selectOne(database));
	}

	@Test
	void fallsBackWhenTheFirstEndpointIsUnreachable() throws Exception {
		final Database database = this.connect(Zone.PUBLIC, List.of(Zone.PRIVATE), dead(SslMode.TRUST), this.working());

		assertEquals(Zone.PRIVATE, database.zone());
		assertEquals(1, selectOne(database));
	}

	@Test
	void fallsBackWhenTheFirstEndpointRejectsTheCredentials() throws Exception {
		final DatabaseEndpoint wrongPassword = new DatabaseEndpoint(this.server.host(), this.server.port(), this.server.user(), "wrong-password", SslMode.TRUST, Map.of());

		assertEquals(Zone.PRIVATE, this.connect(Zone.PUBLIC, List.of(Zone.PRIVATE), wrongPassword, this.working()).zone());
	}

	@Test
	void doesNotFallBackUnlessAskedTo() {
		final SQLException ex = assertThrows(SQLException.class, () -> this.connect(Zone.PUBLIC, List.of(), dead(SslMode.TRUST), this.working()));

		assertTrue(ex.getMessage().contains("public endpoint"), ex.getMessage());
		assertFalse(ex.getMessage().contains("private endpoint"), ex.getMessage());
	}

	@Test
	void reportsEveryEndpointThatFailed() {
		final SQLException ex = assertThrows(SQLException.class, () -> this.connect(Zone.PUBLIC, List.of(Zone.PRIVATE), dead(SslMode.TRUST), dead(SslMode.DISABLE)));

		assertTrue(ex.getMessage().contains("public endpoint 127.0.0.1:1"), ex.getMessage());
		assertTrue(ex.getMessage().contains("private endpoint 127.0.0.1:1"), ex.getMessage());
		assertEquals(2, ex.getCause().getSuppressed().length);
	}

	@Test
	void theErrorNeverContainsPasswords() {
		final SQLException ex = assertThrows(SQLException.class, () -> this.connect(Zone.PRIVATE, List.of(), null, new DatabaseEndpoint("127.0.0.1", 1, "nobody", "hunter2-secret", SslMode.DISABLE, Map.of())));

		assertFalse(ex.getMessage().contains("hunter2-secret"), ex.getMessage());
	}

	@Test
	void transactionsRollBackOnFailure() throws Exception {
		final Database database = this.connect(Zone.PRIVATE, List.of(), null, this.working());

		database.query(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE t (id INT PRIMARY KEY)");
			}

			return null;
		});

		assertThrows(IllegalStateException.class, () -> database.transaction(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO t VALUES (1)")) {
				statement.executeUpdate();
			}

			throw new IllegalStateException("boom");
		}));

		final int rows = database.query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM t")) {
				result.next();

				return result.getInt(1);
			}
		});

		assertEquals(0, rows);
	}

	@Test
	void asyncWorkRunsOnTheDatabaseExecutor() throws Exception {
		final Database database = this.connect(Zone.PRIVATE, List.of(), null, this.working());

		final String thread = database.supplyAsync(() -> Thread.currentThread().getName()).get();

		assertTrue(thread.startsWith("Authenticator-DB-"), thread);
		final Integer one = database.<Integer>queryAsync(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT 1")) {
				result.next();

				return result.getInt(1);
			}
		}).get();

		assertEquals(1, one.intValue());
	}

	@Test
	void theObserverSeesSynchronousCallsAndTheirEnd() throws Exception {
		final Database database = this.connect(Zone.PRIVATE, List.of(), null, this.working());
		final List<String> events = new ArrayList<>();

		database.setObserver(kind -> {
			events.add("begin " + kind);

			return () -> events.add("end " + kind);
		});

		selectOne(database);
		database.transaction(connection -> null);

		assertEquals(List.of("begin query", "end query", "begin transaction", "end transaction"), events);
	}
}
