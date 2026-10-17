package pl.kingdomcraft.authenticator.common.messaging;

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

class RedisConfigTest {

	private static final String SECRET = "0123456789abcdef-test-secret";

	private static Map<String, Object> redis(Object use, Object fallback, Map<String, Object> endpoints, Object... extra) {
		final Map<String, Object> map = new LinkedHashMap<>();

		map.put("secret", SECRET);

		if (use != null)
			map.put("use", use);

		if (fallback != null)
			map.put("fallback", fallback);

		for (int i = 0; i < extra.length; i += 2)
			map.put((String) extra[i], extra[i + 1]);

		map.put("endpoints", endpoints);

		return map;
	}

	private static Map<String, Object> both() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();

		endpoints.put("public", Map.of("host", "redis.example.pl", "user", "proxy", "password", "p4ss"));
		endpoints.put("private", Map.of("host", "10.0.0.6"));

		return endpoints;
	}

	@Test
	void proxyDefaultsToPublicAndBackendToPrivate() {
		assertEquals(Zone.PUBLIC, RedisConfig.parse(redis(null, null, both()), Zone.PUBLIC).zones().use());
		assertEquals(Zone.PRIVATE, RedisConfig.parse(redis(null, null, both()), Zone.PRIVATE).zones().use());
	}

	@Test
	void tlsIsTheDefaultOnlyForThePublicEndpoint() {
		final RedisConfig config = RedisConfig.parse(redis(null, null, both()), Zone.PUBLIC);

		assertTrue(config.zones().endpoints().get(Zone.PUBLIC).ssl());
		assertTrue(config.zones().endpoints().get(Zone.PUBLIC).verifyHostname());
		assertFalse(config.zones().endpoints().get(Zone.PRIVATE).ssl());
	}

	@Test
	void defaultsAreApplied() {
		final RedisConfig config = RedisConfig.parse(redis("private", null, both()), Zone.PRIVATE);

		assertEquals(Channels.DEFAULT_PREFIX, config.prefix());
		assertEquals(30, config.maxSkewSeconds());
		assertEquals(6379, config.zones().endpoints().get(Zone.PRIVATE).port());
		assertEquals(0, config.zones().endpoints().get(Zone.PRIVATE).database());
		assertEquals(5_000, config.zones().endpoints().get(Zone.PRIVATE).connectTimeoutMs());
	}

	@Test
	void candidatesListUseFirstThenFallbacks() {
		assertEquals(List.of(Zone.PRIVATE, Zone.PUBLIC), RedisConfig.parse(redis("private", List.of("public"), both()), Zone.PRIVATE).zones().candidates().stream().map(c -> c.zone()).toList());
	}

	@Test
	void publicEndpointWithoutTlsIsRejected() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("public", Map.of("host", "redis.example.pl", "ssl", false));

		assertThrows(ConfigException.class, () -> RedisConfig.parse(redis("public", null, endpoints), Zone.PUBLIC));
	}

	@Test
	void secretIsRequiredAndMustBeLongEnough() {
		final Map<String, Object> noSecret = redis("private", null, both());
		noSecret.remove("secret");
		assertThrows(ConfigException.class, () -> RedisConfig.parse(noSecret, Zone.PRIVATE));

		assertThrows(ConfigException.class, () -> RedisConfig.parse(redis("private", null, both(), "secret", "short"), Zone.PRIVATE));
	}

	@Test
	void clockSkewIsBounded() {
		assertThrows(ConfigException.class, () -> RedisConfig.parse(redis("private", null, both(), "max-skew-seconds", 0), Zone.PRIVATE));
		assertThrows(ConfigException.class, () -> RedisConfig.parse(redis("private", null, both(), "max-skew-seconds", 301), Zone.PRIVATE));
		assertEquals(120, RedisConfig.parse(redis("private", null, both(), "max-skew-seconds", 120), Zone.PRIVATE).maxSkewSeconds());
	}

	@Test
	void blankPrefixFallsBackToTheDefault() {
		assertEquals(Channels.DEFAULT_PREFIX, RedisConfig.parse(redis("private", null, both(), "prefix", "  "), Zone.PRIVATE).prefix());
		assertEquals("net1:", RedisConfig.parse(redis("private", null, both(), "prefix", "net1:"), Zone.PRIVATE).prefix());
	}

	@Test
	void aTrustStoreForTheServerCertificateCanBeConfigured() {
		final Map<String, Object> endpoints = new LinkedHashMap<>();
		endpoints.put("public", Map.of("host", "redis.example.pl", "ssl-trust-store", "/etc/authenticator/redis.p12", "ssl-trust-store-password", "tr0ub4dor", "verify-hostname", false));

		final RedisEndpoint endpoint = RedisConfig.parse(redis("public", null, endpoints), Zone.PUBLIC).zones().endpoints().get(Zone.PUBLIC);

		assertEquals("/etc/authenticator/redis.p12", endpoint.trustStore());
		assertEquals("tr0ub4dor", endpoint.trustStorePassword());
		assertFalse(endpoint.verifyHostname());
		assertFalse(endpoint.toString().contains("tr0ub4dor"));
		assertEquals("", RedisConfig.parse(redis(null, null, both()), Zone.PUBLIC).zones().endpoints().get(Zone.PUBLIC).trustStore());
	}

	@Test
	void secretsAndPasswordsNeverAppearInText() {
		final RedisConfig config = RedisConfig.parse(redis("public", null, both()), Zone.PUBLIC);

		assertFalse(config.toString().contains(SECRET));
		assertFalse(config.zones().endpoints().get(Zone.PUBLIC).toString().contains("p4ss"));
	}

	@Test
	void channelsAreNamespacedByPrefix() {
		final Channels channels = new Channels("net1:");

		assertEquals("net1:state", channels.state());
		assertEquals("net1:admin", channels.admin());
		assertEquals("net1:node:velocity-1", channels.nodeKey("velocity-1"));
		assertEquals("net1:state:069a79f4-44e9-4726-a5be-fca90e38aaf5", channels.stateKey(java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
		assertThrows(IllegalArgumentException.class, () -> new Channels(" "));
	}
}
