package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

/**
 * The public endpoint over TLS. Needs a MariaDB with TLS enabled whose certificate is valid for the DNS name {@code localhost} but not
 * for the IP address 127.0.0.1, reachable on the port in {@code AUTHENTICATOR_TEST_MARIADB}. Set {@code AUTHENTICATOR_TEST_MARIADB_CERT}
 * to that certificate (PEM) and {@code AUTHENTICATOR_TEST_MARIADB_OTHER_CERT} to any other one. Skipped otherwise.
 */
class DatabaseTlsIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");

	private static String env(String name) {
		final String value = System.getenv(name);

		Assumptions.assumeTrue(value != null && !value.isBlank(), "set " + name + " to run the TLS tests");

		return value;
	}

	/** The driver wants a URI here; a bare file path fails with "URI is not absolute". */
	private static String uri(String path) {
		return Path.of(path).toUri().toString();
	}

	private static TestMariaDb.Server at(TestMariaDb.Server server, String host) {
		return new TestMariaDb.Server(host, server.port(), server.user(), server.password());
	}

	private static Database connect(TestMariaDb.Server server, String schema, SslMode mode, Map<String, String> properties) throws SQLException {
		final ZonedEndpoints<DatabaseEndpoint> zones = new ZonedEndpoints<>(Zone.PUBLIC, List.of(), Map.of(Zone.PUBLIC, server.endpoint(mode, properties)));

		return Database.connect(new DatabaseConfig(schema, zones, 2, 3_000), LOGGER);
	}

	private static String cipher(Database database) throws SQLException {
		return database.query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
				result.next();

				return result.getString(2);
			}
		});
	}

	/** Every message in the failure, including the ones of each endpoint that was tried. */
	private static String reasons(Throwable failure) {
		final StringBuilder text = new StringBuilder();

		for (Throwable current = failure; current != null; current = current.getCause()) {
			text.append(current.getMessage()).append(" | ");

			for (final Throwable suppressed : current.getSuppressed())
				text.append(reasons(suppressed));
		}

		return text.toString();
	}

	@Test
	void trustEncryptsTheConnection() throws Exception {
		final TestMariaDb.Server server = TestMariaDb.requireServer();
		env("AUTHENTICATOR_TEST_MARIADB_CERT");

		try (TestMariaDb.Schema schema = TestMariaDb.createSchema(false); Database database = connect(server, schema.name(), SslMode.TRUST, Map.of())) {
			assertEquals(Zone.PUBLIC, database.zone());
			assertFalse(cipher(database).isEmpty(), "the session is not encrypted");
		}
	}

	@Test
	void verifyCaAcceptsTheServersOwnCertificate() throws Exception {
		final TestMariaDb.Server server = TestMariaDb.requireServer();
		final String cert = env("AUTHENTICATOR_TEST_MARIADB_CERT");

		try (TestMariaDb.Schema schema = TestMariaDb.createSchema(false); Database database = connect(server, schema.name(), SslMode.VERIFY_CA, Map.of("serverSslCert", uri(cert)))) {
			assertFalse(cipher(database).isEmpty());
		}
	}

	@Test
	void verifyFullAcceptsAHostNameInTheCertificate() throws Exception {
		final TestMariaDb.Server server = at(TestMariaDb.requireServer(), "localhost");
		final String cert = env("AUTHENTICATOR_TEST_MARIADB_CERT");

		try (TestMariaDb.Schema schema = TestMariaDb.createSchema(false); Database database = connect(server, schema.name(), SslMode.VERIFY_FULL, Map.of("serverSslCert", uri(cert)))) {
			assertFalse(cipher(database).isEmpty());
		}
	}

	@Test
	void verifyCaRejectsAnotherCertificate() throws Exception {
		final TestMariaDb.Server server = TestMariaDb.requireServer();
		env("AUTHENTICATOR_TEST_MARIADB_CERT");
		final String other = env("AUTHENTICATOR_TEST_MARIADB_OTHER_CERT");

		try (TestMariaDb.Schema schema = TestMariaDb.createSchema(false)) {
			final SQLException failure = assertThrows(SQLException.class, () -> connect(server, schema.name(), SslMode.VERIFY_CA, Map.of("serverSslCert", uri(other))));
			final String reasons = reasons(failure);

			assertFalse(reasons.contains("not absolute"), "failed for the wrong reason: " + reasons);
			assertTrue(reasons.toLowerCase().matches("(?s).*(certificate|pkix|trust).*"), "expected a certificate error, got: " + reasons);
		}
	}

	@Test
	void verifyFullRejectsAHostNameThatIsNotInTheCertificate() throws Exception {
		final TestMariaDb.Server server = at(TestMariaDb.requireServer(), "127.0.0.1");
		final String cert = env("AUTHENTICATOR_TEST_MARIADB_CERT");

		try (TestMariaDb.Schema schema = TestMariaDb.createSchema(false)) {
			// The same certificate is accepted by verify-ca, so only the name check can be what fails here.
			try (Database database = connect(server, schema.name(), SslMode.VERIFY_CA, Map.of("serverSslCert", uri(cert)))) {
				assertFalse(cipher(database).isEmpty());
			}

			final SQLException failure = assertThrows(SQLException.class, () -> connect(server, schema.name(), SslMode.VERIFY_FULL, Map.of("serverSslCert", uri(cert))));

			assertTrue(reasons(failure).toLowerCase().matches("(?s).*(identity|subject alternative|hostname|host name|doesn't match).*"), "expected a host name error, got: " + reasons(failure));
		}
	}
}
