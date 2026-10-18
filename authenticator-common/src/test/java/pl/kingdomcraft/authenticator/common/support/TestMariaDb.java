package pl.kingdomcraft.authenticator.common.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Assumptions;

import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.DatabaseConfig;
import pl.kingdomcraft.authenticator.common.database.DatabaseEndpoint;
import pl.kingdomcraft.authenticator.common.database.Migrator;
import pl.kingdomcraft.authenticator.common.database.SslMode;

/**
 * A real MariaDB 10.11+ for the integration tests, taken from the environment: {@code AUTHENTICATOR_TEST_MARIADB=host:port:user:password}.
 * The user must be allowed to create and drop schemas named {@code authtest_*}. Tests are skipped when the variable is not set.
 *
 * <p>Every test gets its own empty schema, dropped afterwards.
 */
public final class TestMariaDb {

	public static final String ENV = "AUTHENTICATOR_TEST_MARIADB";

	public record Server(String host, int port, String user, String password) {

		public DatabaseEndpoint endpoint(SslMode sslMode, Map<String, String> properties) {
			return new DatabaseEndpoint(this.host, this.port, this.user, this.password, sslMode, properties);
		}
	}

	private TestMariaDb() {
	}

	/** The configured server, or null when the environment does not provide one. */
	public static Server server() {
		final String value = System.getenv(ENV);

		if (value == null || value.isBlank())
			return null;

		final String[] parts = value.split(":", 4);

		if (parts.length != 4)
			throw new IllegalStateException(ENV + " must look like host:port:user:password");

		return new Server(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3]);
	}

	public static Server requireServer() {
		final Server server = server();

		Assumptions.assumeTrue(server != null, "set " + ENV + "=host:port:user:password to run the MariaDB integration tests");

		return server;
	}

	/** @param migrate whether to apply the bundled migrations, so the schema is ready to use */
	public static Schema createSchema(boolean migrate) throws SQLException {
		final Server server = requireServer();
		final String name = "authtest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

		try (Connection connection = DriverManager.getConnection("jdbc:mariadb://" + server.host() + ":" + server.port() + "/", server.user(), server.password());
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4");
		}

		final DatabaseConfig config = new DatabaseConfig(name, new ZonedEndpoints<>(Zone.PRIVATE, List.of(), Map.of(Zone.PRIVATE, server.endpoint(SslMode.DISABLE, Map.of()))), 4, 5_000);
		final Database database = Database.connect(config, Logger.getLogger("test"));

		if (migrate)
			new Migrator(database, Logger.getLogger("test")).migrate();

		return new Schema(server, name, config, database);
	}

	/** An empty schema and a connection pool on it. */
	public static final class Schema implements AutoCloseable {

		private final Server server;
		private final String name;
		private final DatabaseConfig config;
		private final Database database;

		private Schema(Server server, String name, DatabaseConfig config, Database database) {
			this.server = server;
			this.name = name;
			this.config = config;
			this.database = database;
		}

		public String name() {
			return this.name;
		}

		public DatabaseConfig config() {
			return this.config;
		}

		public Database database() {
			return this.database;
		}

		@Override
		public void close() throws SQLException {
			this.database.close();

			try (Connection connection = DriverManager.getConnection("jdbc:mariadb://" + this.server.host() + ":" + this.server.port() + "/", this.server.user(), this.server.password());
					Statement statement = connection.createStatement()) {
				statement.execute("DROP DATABASE IF EXISTS " + this.name);
			}
		}
	}
}
