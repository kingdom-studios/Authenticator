package pl.kingdomcraft.authenticator.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import pl.kingdomcraft.authenticator.common.database.DatabaseConfig;
import pl.kingdomcraft.authenticator.common.database.DatabaseEndpoint;
import pl.kingdomcraft.authenticator.common.messaging.RedisConfig;

/** The config files shipped by the platform modules must load through the same parsers the plugins use. */
class SampleConfigsTest {

	private static final Path VELOCITY = Path.of("..", "authenticator-velocity", "src", "main", "resources", "config.yml");
	private static final Path PAPER = Path.of("..", "authenticator-paper", "src", "main", "resources", "settings.yml");

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file)) {
			return (Map<String, Object>) new Yaml().load(reader);
		}
	}

	private static Map<String, Object> section(Map<String, Object> root, String name) {
		for (final Map.Entry<String, Object> entry : root.entrySet())
			if (entry.getKey().equalsIgnoreCase(name))
				return withSecret(entry.getValue());

		throw new AssertionError("no '" + name + "' section");
	}

	/** The samples ship an empty secret on purpose, so that nobody runs with a made-up one. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> withSecret(Object value) {
		final Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) value);

		for (final String key : List.copyOf(copy.keySet()))
			if (key.equalsIgnoreCase("secret"))
				copy.put(key, "0123456789abcdef-test-secret");

		return copy;
	}

	@Test
	void theVelocitySampleLoadsAndUsesThePublicEndpoints() throws IOException {
		final Map<String, Object> root = load(VELOCITY);

		assertNotNull(root.get("node-id"));

		final DatabaseConfig database = DatabaseConfig.parse(section(root, "database"), Zone.PRIVATE);
		final RedisConfig redis = RedisConfig.parse(section(root, "redis"), Zone.PRIVATE);

		assertEquals(Zone.PUBLIC, database.zones().use());
		assertEquals(Zone.PUBLIC, redis.zones().use());
		assertEquals(2, database.zones().endpoints().size());
		assertEquals(2, redis.zones().endpoints().size());
		assertEquals("authenticator", database.database());
		assertTrue(redis.zones().endpoints().get(Zone.PUBLIC).ssl());
	}

	@Test
	void thePaperSampleLoadsAndUsesThePrivateEndpoints() throws IOException {
		final Map<String, Object> root = load(PAPER);

		assertNotNull(root.get("Node_Id"));

		final DatabaseConfig database = DatabaseConfig.parse(section(root, "Database"), Zone.PUBLIC);
		final RedisConfig redis = RedisConfig.parse(section(root, "Redis"), Zone.PUBLIC);

		assertEquals(Zone.PRIVATE, database.zones().use());
		assertEquals(Zone.PRIVATE, redis.zones().use());
		assertEquals(2, database.zones().endpoints().size());
		assertEquals(2, redis.zones().endpoints().size());
	}

	@Test
	void bothSamplesDescribeTheSameDatabaseAndRedisLayout() throws IOException {
		final DatabaseConfig proxy = DatabaseConfig.parse(section(load(VELOCITY), "database"), Zone.PRIVATE);
		final DatabaseConfig backend = DatabaseConfig.parse(section(load(PAPER), "Database"), Zone.PUBLIC);

		assertEquals(proxy.database(), backend.database());

		for (final Zone zone : Zone.values()) {
			final DatabaseEndpoint a = proxy.zones().endpoints().get(zone);
			final DatabaseEndpoint b = backend.zones().endpoints().get(zone);

			assertEquals(a.host(), b.host(), zone + " host differs between the two samples");
			assertEquals(a.port(), b.port());
			assertEquals(a.sslMode(), b.sslMode());
		}

		assertEquals(RedisConfig.parse(section(load(VELOCITY), "redis"), Zone.PRIVATE).prefix(), RedisConfig.parse(section(load(PAPER), "Redis"), Zone.PUBLIC).prefix());
	}

	@Test
	void theSamplesRefuseToRunWithoutASecret() throws IOException {
		final Map<String, Object> velocity = new LinkedHashMap<>(load(VELOCITY));

		@SuppressWarnings("unchecked")
		final Map<String, Object> redis = (Map<String, Object>) velocity.get("redis");

		assertThrows(ConfigException.class, () -> RedisConfig.parse(redis, Zone.PRIVATE));
	}
}
