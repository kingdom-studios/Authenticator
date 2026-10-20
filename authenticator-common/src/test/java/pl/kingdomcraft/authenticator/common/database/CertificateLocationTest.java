package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;

class CertificateLocationTest {

	@TempDir
	Path dir;

	private Path pem(String name) throws Exception {
		return Files.writeString(this.dir.resolve(name), "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
	}

	private DatabaseEndpoint parse(Map<String, Object> endpoint) {
		final Map<String, Object> values = new java.util.LinkedHashMap<>(Map.of("host", "db.example.com", "user", "u", "ssl-mode", "verify-full"));
		values.putAll(endpoint);

		return DatabaseEndpoint.parse(Zone.PUBLIC, ConfigSection.of("database.endpoints.public", values), this.dir);
	}

	@Test
	void anAbsolutePathBecomesAFileUri() throws Exception {
		final Path file = this.pem("ca.pem");

		assertEquals(file.toUri().toString(), CertificateLocation.normalize("k", file.toString(), Path.of("/elsewhere")));
		assertTrue(CertificateLocation.normalize("k", file.toString(), this.dir).startsWith("file:///"));
	}

	@Test
	void aRelativePathIsLookedForInTheDataDirectory() throws Exception {
		final Path file = this.pem("devkdc-db-ca.pem");

		assertEquals(file.toUri().toString(), CertificateLocation.normalize("k", "devkdc-db-ca.pem", this.dir));

		Files.createDirectories(this.dir.resolve("certs"));
		Files.copy(file, this.dir.resolve("certs/other.pem"));

		assertEquals(this.dir.resolve("certs/other.pem").toUri().toString(), CertificateLocation.normalize("k", "certs/other.pem", this.dir));
	}

	@Test
	void uriAndPemTextAreLeftAlone() {
		for (final String value : new String[] { "file:///no/such/file.pem", "classpath:ca.pem", "-----BEGIN CERTIFICATE-----MIIB-----END CERTIFICATE-----" })
			assertEquals(value, CertificateLocation.normalize("k", value, this.dir));
	}

	@Test
	void aMissingFileIsReportedWithItsFullPath() {
		final ConfigException absolute = assertThrows(ConfigException.class, () -> CertificateLocation.normalize("database.endpoints.public.server-ssl-cert", "/no/such/ca.pem", this.dir));

		assertTrue(absolute.getMessage().contains("/no/such/ca.pem"), absolute.getMessage());
		assertTrue(absolute.getMessage().contains("database.endpoints.public.server-ssl-cert"), absolute.getMessage());

		final ConfigException relative = assertThrows(ConfigException.class, () -> CertificateLocation.normalize("k", "missing.pem", this.dir));

		assertTrue(relative.getMessage().contains(this.dir.resolve("missing.pem").toString()), relative.getMessage());
		assertTrue(relative.getMessage().contains("looked for in"), relative.getMessage());
	}

	@Test
	void aDirectoryAndAnEmptyFileAreRefused() throws Exception {
		assertTrue(assertThrows(ConfigException.class, () -> CertificateLocation.normalize("k", this.dir.toString(), this.dir)).getMessage().contains("is not a file"));

		Files.createFile(this.dir.resolve("empty.pem"));

		assertTrue(assertThrows(ConfigException.class, () -> CertificateLocation.normalize("k", "empty.pem", this.dir)).getMessage().contains("empty"));
	}

	@Test
	void anUnreadableFileIsReported() throws Exception {
		final Path file = this.pem("locked.pem");

		if (!file.toFile().setReadable(false) || Files.isReadable(file))
			return; // running as root or on a file system without permissions: nothing to test

		assertTrue(assertThrows(ConfigException.class, () -> CertificateLocation.normalize("k", file.toString(), this.dir)).getMessage().contains("cannot be read"));
	}

	@Test
	void theDedicatedKeyEndsUpAsTheDriverOption() throws Exception {
		final Path file = this.pem("ca.pem");
		final DatabaseEndpoint endpoint = this.parse(Map.of("server-ssl-cert", "ca.pem"));

		assertEquals(file.toUri().toString(), endpoint.properties().get("serverSslCert"));
	}

	@Test
	void theOptionInPropertiesIsNormalisedInAnyCapitalisation() throws Exception {
		final Path file = this.pem("ca.pem");
		final DatabaseEndpoint endpoint = this.parse(Map.of("properties", Map.of("serversslcert", file.toString(), "tcpKeepAlive", "true")));

		assertEquals(Map.of("serverSslCert", file.toUri().toString(), "tcpKeepAlive", "true"), endpoint.properties());
	}

	@Test
	void theDedicatedKeyWinsOverProperties() throws Exception {
		final Path file = this.pem("ca.pem");

		this.pem("other.pem");

		final DatabaseEndpoint endpoint = this.parse(Map.of("server-ssl-cert", "ca.pem", "properties", Map.of("serverSslCert", "other.pem")));

		assertEquals(file.toUri().toString(), endpoint.properties().get("serverSslCert"));
		assertEquals(1, endpoint.properties().size());
	}

	@Test
	void anEmptyKeyChangesNothingAndTheUrlCarriesTheUri() throws Exception {
		assertFalse(this.parse(Map.of("server-ssl-cert", "")).properties().containsKey("serverSslCert"));

		final Path file = this.pem("ca.pem");
		final DatabaseConfig config = DatabaseConfig.parse(Map.of("name", "authenticator", "endpoints", Map.of("public", Map.of("host", "db.example.com", "user", "u", "server-ssl-cert", file.toString()))), Zone.PUBLIC, this.dir);

		assertTrue(config.jdbcUrl(config.zones().endpoints().get(Zone.PUBLIC)).contains("serverSslCert=" + file.toUri()));
	}

	@Test
	void aMissingFileStopsTheConfigWithItsPath() {
		final ConfigException ex = assertThrows(ConfigException.class, () -> this.parse(Map.of("server-ssl-cert", "nope.pem")));

		assertTrue(ex.getMessage().contains(this.dir.resolve("nope.pem").toString()), ex.getMessage());
	}
}
