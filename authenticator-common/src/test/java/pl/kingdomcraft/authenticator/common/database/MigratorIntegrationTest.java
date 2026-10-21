package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

class MigratorIntegrationTest {

	private TestMariaDb.Schema schema;
	private Migrator migrator;

	@BeforeEach
	void setUp() throws Exception {
		this.schema = TestMariaDb.createSchema(false);
		this.migrator = new Migrator(this.schema.database(), Logger.getLogger("test"));
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.schema != null)
			this.schema.close();
	}

	private long count(String sql) throws Exception {
		try (Connection connection = this.schema.database().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			result.next();

			return result.getLong(1);
		}
	}

	@Test
	void createsEveryTable() throws Exception {
		assertEquals(Migrator.latestVersion(), this.migrator.migrate());

		final Set<String> tables = new HashSet<>();

		try (Connection connection = this.schema.database().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW TABLES")) {
			while (result.next())
				tables.add(result.getString(1));
		}

		for (final String expected : List.of("auth_schema_version", "auth_accounts", "auth_sessions", "auth_presence", "auth_ip_blocks", "auth_premium_cache", "auth_audit"))
			assertTrue(tables.contains(expected), "missing table " + expected);
	}

	@Test
	void isIdempotent() throws Exception {
		assertEquals(4, this.migrator.migrate());
		assertEquals(4, this.migrator.migrate());
		assertEquals(4L, this.count("SELECT COUNT(*) FROM auth_schema_version"));
	}

	@Test
	void concurrentNodesApplyEachMigrationOnce() throws Exception {
		final List<CompletableFuture<Integer>> runs = java.util.stream.IntStream.range(0, 4).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
			try {
				return new Migrator(this.schema.database(), Logger.getLogger("test-" + i)).migrate();
			} catch (final Exception ex) {
				throw new RuntimeException(ex);
			}
		})).toList();

		for (final CompletableFuture<Integer> run : runs)
			assertEquals(4, run.get());

		assertEquals(4L, this.count("SELECT COUNT(*) FROM auth_schema_version"));
	}

	@Test
	void verifyReportsAnUnmigratedDatabaseAndNeverChangesIt() throws Exception {
		final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> this.migrator.verify());

		assertTrue(ex.getMessage().contains("version 0"), ex.getMessage());
		assertTrue(ex.getMessage().contains("Start the proxy"), ex.getMessage());

		try (Connection connection = this.schema.database().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW TABLES")) {
			assertTrue(!result.next(), "verify must not create anything");
		}
	}

	@Test
	void verifyAcceptsAMigratedDatabase() throws Exception {
		this.migrator.migrate();

		assertEquals(4, this.migrator.verify());
	}

	@Test
	void aPartlyMigratedDatabaseIsNotVerified() throws Exception {
		this.migrator.migrate();

		try (Connection connection = this.schema.database().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM auth_schema_version WHERE version > 2");
		}

		assertTrue(assertThrows(IllegalStateException.class, () -> this.migrator.verify()).getMessage().contains("version 2"));
	}

	@Test
	void aSchemaNewerThanTheBuildIsRefused() throws Exception {
		this.migrator.migrate();

		try (Connection connection = this.schema.database().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO auth_schema_version (version, description) VALUES (99, 'from the future')");
		}

		assertTrue(assertThrows(IllegalStateException.class, () -> this.migrator.migrate()).getMessage().contains("Update Authenticator"));
		assertTrue(assertThrows(IllegalStateException.class, () -> this.migrator.verify()).getMessage().contains("Update Authenticator"));
	}
}
