package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.Zone;

class DatabaseConfigTest {

	private static Map<String, Object> endpoint(String host, Object... extra) {
		final Map<String, Object> map = new LinkedHashMap<>();

		map.put("host", host);
		map.put("user", "auth");
		map.put("password", "s3cret");

		for (int i = 0; i < extra.length; i += 2)
			map.put((String) extra[i], extra[i + 1]);

		return map;
	}

	private static Map<String, Object> database(Object use, Object fallback, Map<String, Object> endpoints) {
		final Map<String, Object> map = new LinkedHashMap<>();

		map.put("name", "authenticator");

		if (use != null)
			map.put("use", use);

		if (fallback != null)
			map.put("fallback", fallback);

		map.put("endpoints", endpoints);

		return map;
	}

	private static Map<String, Object> both() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();

		endpoints.put("public", endpoint("db.example.pl"));
		endpoints.put("private", endpoint("10.0.0.5"));

		return endpoints;
	}

	@Test
	void proxyDefaultsToPublicAndBackendToPrivate() {
		final Map<String, Object> raw = database(null, null, both());

		assertEquals(Zone.PUBLIC, DatabaseConfig.parse(raw, Zone.PUBLIC).zones().use());
		assertEquals(Zone.PRIVATE, DatabaseConfig.parse(raw, Zone.PRIVATE).zones().use());
	}

	@Test
	void explicitUseWinsOverTheDefault() {
		assertEquals(Zone.PRIVATE, DatabaseConfig.parse(database("private", null, both()), Zone.PUBLIC).zones().use());
	}

	@Test
	void tlsIsTheDefaultOnlyForThePublicEndpoint() {
		final DatabaseConfig config = DatabaseConfig.parse(database(null, null, both()), Zone.PUBLIC);

		assertEquals(SslMode.VERIFY_FULL, config.zones().endpoints().get(Zone.PUBLIC).sslMode());
		assertEquals(SslMode.DISABLE, config.zones().endpoints().get(Zone.PRIVATE).sslMode());
	}

	@Test
	void foundationStyleKeysAreAccepted() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("Private", Map.of("Host", "10.0.0.5", "Port", 3307, "User", "u", "Password", "p"));

		final Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("Name", "authenticator");
		raw.put("Use", "private");
		raw.put("Pool_Size", 4);
		raw.put("Connection_Timeout_Ms", 2000);
		raw.put("Endpoints", endpoints);

		final DatabaseConfig config = DatabaseConfig.parse(raw, Zone.PRIVATE);

		assertEquals(4, config.poolSize());
		assertEquals(2000, config.connectionTimeoutMs());
		assertEquals(3307, config.zones().endpoints().get(Zone.PRIVATE).port());
	}

	@Test
	void candidatesListUseFirstThenFallbacks() {
		final DatabaseConfig config = DatabaseConfig.parse(database("public", List.of("private"), both()), Zone.PUBLIC);

		assertEquals(List.of(Zone.PUBLIC, Zone.PRIVATE), config.zones().candidates().stream().map(c -> c.zone()).toList());
	}

	@Test
	void usingAZoneWithoutAnEndpointIsRejected() {
		final ConfigException ex = assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("private", null, Map.of("public", endpoint("db.example.pl"))), Zone.PRIVATE));

		assertTrue(ex.getMessage().contains("private"), ex.getMessage());
	}

	@Test
	void fallbackMustExistAndMustNotRepeatUse() {
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("public", List.of("private"), Map.of("public", endpoint("db.example.pl"))), Zone.PUBLIC));
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("public", List.of("public"), both()), Zone.PUBLIC));
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("public", List.of("private", "private"), both()), Zone.PUBLIC));
	}

	@Test
	void unknownZoneNamesAreRejected() {
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("internal", null, both()), Zone.PUBLIC));

		final Map<String, Object> endpoints = both();
		endpoints.put("internal", endpoint("x"));
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database(null, null, endpoints), Zone.PUBLIC));
	}

	@Test
	void publicEndpointCannotUsePlainText() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("public", endpoint("db.example.pl", "ssl-mode", "disable"));

		final ConfigException ex = assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("public", null, endpoints), Zone.PUBLIC));

		assertTrue(ex.getMessage().contains("public"), ex.getMessage());
	}

	@Test
	void privateEndpointMayBePlainTextOrEncrypted() {
		for (final String mode : new String[] { "disable", "trust", "verify-ca", "verify_full" }) {
			final Map<String, Object> endpoints = new LinkedHashMap<>();
			endpoints.put("private", endpoint("10.0.0.5", "ssl-mode", mode));

			assertEquals(SslMode.parse("x", mode), DatabaseConfig.parse(database("private", null, endpoints), Zone.PRIVATE).zones().endpoints().get(Zone.PRIVATE).sslMode());
		}
	}

	@Test
	void missingRequiredValuesNameTheirPath() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("private", Map.of("host", "10.0.0.5"));

		final ConfigException ex = assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("private", null, endpoints), Zone.PRIVATE));

		assertTrue(ex.getMessage().startsWith("database.endpoints.private.user"), ex.getMessage());
	}

	@Test
	void invalidValuesAreRejected() {
		final Map<String, Object> badName = database("private", null, Map.of("private", endpoint("10.0.0.5")));
		badName.put("name", "auth;DROP");
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(badName, Zone.PRIVATE));

		final Map<String, Object> badPool = database("private", null, Map.of("private", endpoint("10.0.0.5")));
		badPool.put("pool-size", 0);
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(badPool, Zone.PRIVATE));

		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("private", null, Map.of("private", endpoint("10.0.0.5", "port", 70000))), Zone.PRIVATE));
		assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("private", null, Map.of("private", endpoint("10.0.0.5", "ssl-mode", "maybe"))), Zone.PRIVATE));
	}

	@Test
	void jdbcUrlCarriesSecurityAndTimeoutDefaults() {
		final DatabaseConfig config = DatabaseConfig.parse(database("public", null, both()), Zone.PUBLIC);
		final String url = config.jdbcUrl(config.zones().endpoints().get(Zone.PUBLIC));

		assertTrue(url.startsWith("jdbc:mariadb://db.example.pl:3306/authenticator?"), url);
		assertTrue(url.contains("sslMode=verify-full"), url);
		assertTrue(url.contains("connectionTimeZone=UTC"), url);
		assertTrue(url.contains("forceConnectionTimeZoneToSession=true"), url);
		assertTrue(url.contains("connectTimeout=10000"), url);
		assertTrue(url.contains("socketTimeout=60000"), url);
	}

	@Test
	void propertiesOverrideDefaultsAndAreWrittenAsTheyAre() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("private", endpoint("10.0.0.5", "properties", Map.of("socketTimeout", "5000", "serverSslCert", "file:///etc/ssl/db ca.pem")));

		final DatabaseConfig config = DatabaseConfig.parse(database("private", null, endpoints), Zone.PRIVATE);
		final String url = config.jdbcUrl(config.zones().endpoints().get(Zone.PRIVATE));

		assertTrue(url.contains("socketTimeout=5000"), url);
		assertFalse(url.contains("socketTimeout=60000"), url);

		// The driver does not decode option values, so a URI must not be percent-encoded.
		assertTrue(url.contains("serverSslCert=file:///etc/ssl/db ca.pem"), url);
	}

	@Test
	void propertiesThatCouldBreakTheUrlAreRejected() {
		for (final Map<String, String> bad : List.of(Map.of("a", "x&sslMode=disable"), Map.of("a", "x\ny"), Map.of("a=b", "x"), Map.of("a&b", "x"), Map.of("", "x"), Map.of("1abc", "x"))) {
			final Map<String, Object> endpoints = new LinkedHashMap<>();
			endpoints.put("private", endpoint("10.0.0.5", "properties", bad));

			assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("private", null, endpoints), Zone.PRIVATE), bad.toString());
		}
	}

	@Test
	void hostsThatCouldBreakTheUrlAreRejected() {
		for (final String host : List.of("db/other", "db?x=1", "db host"))
			assertThrows(ConfigException.class, () -> DatabaseConfig.parse(database("private", null, Map.of("private", endpoint(host))), Zone.PRIVATE), host);
	}

	@Test
	void ipv6HostsAreBracketed() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("private", endpoint("fd00::5"));

		final DatabaseConfig config = DatabaseConfig.parse(database("private", null, endpoints), Zone.PRIVATE);

		assertTrue(config.jdbcUrl(config.zones().endpoints().get(Zone.PRIVATE)).startsWith("jdbc:mariadb://[fd00::5]:3306/"));
		assertEquals("[fd00::5]:3306", config.zones().endpoints().get(Zone.PRIVATE).address());
	}

	@Test
	void passwordsNeverAppearInUrlsOrText() {
		final DatabaseConfig config = DatabaseConfig.parse(database("public", null, both()), Zone.PUBLIC);
		final DatabaseEndpoint endpoint = config.zones().endpoints().get(Zone.PUBLIC);

		assertFalse(config.jdbcUrl(endpoint).contains("s3cret"));
		assertFalse(endpoint.toString().contains("s3cret"));
		assertFalse(config.toString().contains("s3cret"));
	}
}
