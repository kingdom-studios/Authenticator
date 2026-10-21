package pl.kingdomcraft.authenticator.common.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.Zone;

class BridgeConfigTest {

	private static final String SECRET = "0123456789abcdef-test-secret";

	private static Map<String, Object> redis() {
		return Map.of("use", "private", "endpoints", Map.of("private", Map.of("host", "10.0.0.6")));
	}

	private static Map<String, Object> root(Map<String, Object> bridge, Map<String, Object> redis) {
		final Map<String, Object> root = new LinkedHashMap<>();

		if (bridge != null)
			root.put("bridge", bridge);

		if (redis != null)
			root.put("redis", redis);

		return root;
	}

	private static Map<String, Object> bridge(Object... pairs) {
		final Map<String, Object> bridge = new LinkedHashMap<>();

		for (int i = 0; i < pairs.length; i += 2)
			bridge.put((String) pairs[i], pairs[i + 1]);

		return bridge;
	}

	@Test
	void pluginMessagingIsTheDefaultAndNeedsNoRedis() {
		final BridgeConfig config = BridgeConfig.parse(root(bridge("secret", SECRET), null), Zone.PUBLIC);

		assertEquals(BridgeMode.PLUGIN_MESSAGING, config.mode());
		assertNull(config.redis());
		assertEquals(30, config.maxSkewSeconds());
		assertEquals(5, config.databasePollSeconds());
	}

	@Test
	void aRedisSectionIsIgnoredUnlessTheModeIsRedis() {
		final Map<String, Object> brokenRedis = Map.of("use", "private");

		assertNull(BridgeConfig.parse(root(bridge("secret", SECRET), brokenRedis), Zone.PUBLIC).redis());
		assertNull(BridgeConfig.parse(root(bridge("mode", "database-only"), brokenRedis), Zone.PUBLIC).redis());
	}

	@Test
	void redisModeReadsTheRedisSection() {
		final BridgeConfig config = BridgeConfig.parse(root(bridge("mode", "redis", "secret", SECRET), redis()), Zone.PRIVATE);

		assertEquals(BridgeMode.REDIS, config.mode());
		assertEquals(Zone.PRIVATE, config.redis().zones().use());
	}

	@Test
	void redisModeWithoutARedisSectionIsRefused() {
		final ConfigException ex = assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("mode", "redis", "secret", SECRET), null), Zone.PUBLIC));

		assertTrue(ex.getMessage().contains("redis"), ex.getMessage());
	}

	@Test
	void databaseOnlyNeedsNoSecret() {
		final BridgeConfig config = BridgeConfig.parse(root(bridge("mode", "database-only"), null), Zone.PUBLIC);

		assertEquals(BridgeMode.DATABASE_ONLY, config.mode());
		assertEquals("", config.secret());
	}

	@Test
	void everyModeThatSendsMessagesNeedsALongSecret() {
		for (final String mode : new String[] { "plugin-messaging", "redis" }) {
			assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("mode", mode), redis()), Zone.PRIVATE), mode + " without a secret");
			assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("mode", mode, "secret", "short"), redis()), Zone.PRIVATE), mode + " with a short secret");
		}
	}

	@Test
	void keysAreAcceptedInBothStyles() {
		final Map<String, Object> root = new LinkedHashMap<>();
		root.put("Bridge", bridge("Mode", "Plugin_Messaging", "Secret", SECRET, "Max_Skew_Seconds", 60, "Database_Poll_Seconds", 0));

		final BridgeConfig config = BridgeConfig.parse(root, Zone.PRIVATE);

		assertEquals(BridgeMode.PLUGIN_MESSAGING, config.mode());
		assertEquals(60, config.maxSkewSeconds());
		assertEquals(0, config.databasePollSeconds());
	}

	@Test
	void unknownModesAndOutOfRangeValuesAreRefused() {
		assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("mode", "carrier-pigeon", "secret", SECRET), null), Zone.PUBLIC));
		assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("secret", SECRET, "max-skew-seconds", 0), null), Zone.PUBLIC));
		assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("secret", SECRET, "max-skew-seconds", 301), null), Zone.PUBLIC));
		assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(bridge("secret", SECRET, "database-poll-seconds", -1), null), Zone.PUBLIC));
		assertThrows(ConfigException.class, () -> BridgeConfig.parse(root(null, null), Zone.PUBLIC));
	}

	@Test
	void theSecretNeverAppearsInText() {
		assertFalse(BridgeConfig.parse(root(bridge("secret", SECRET), null), Zone.PUBLIC).toString().contains(SECRET));
	}
}
