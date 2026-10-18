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

import pl.kingdomcraft.authenticator.common.auth.PremiumPolicy;
import pl.kingdomcraft.authenticator.common.bridge.BridgeConfig;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.database.DatabaseConfig;
import pl.kingdomcraft.authenticator.common.database.DatabaseEndpoint;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.messaging.RedisConfig;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;

/** The config files shipped by the platform modules must load through the same parsers the plugins use. */
class SampleConfigsTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Path VELOCITY = Path.of("..", "authenticator-velocity", "src", "main", "resources", "config.yml");
	private static final Path PAPER = Path.of("..", "authenticator-paper", "src", "main", "resources", "settings.yml");

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file)) {
			return (Map<String, Object>) new Yaml().load(reader);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> section(Map<String, Object> root, String name) {
		for (final Map.Entry<String, Object> entry : root.entrySet())
			if (entry.getKey().equalsIgnoreCase(name))
				return (Map<String, Object>) entry.getValue();

		throw new AssertionError("no '" + name + "' section");
	}

	/** The samples ship an empty secret on purpose, so that nobody runs with a made-up one. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> withSecret(Map<String, Object> root) {
		final Map<String, Object> copy = new LinkedHashMap<>(root);

		for (final String key : List.copyOf(copy.keySet()))
			if (key.equalsIgnoreCase("bridge")) {
				final Map<String, Object> bridge = new LinkedHashMap<>((Map<String, Object>) copy.get(key));

				for (final String bridgeKey : List.copyOf(bridge.keySet()))
					if (bridgeKey.equalsIgnoreCase("secret"))
						bridge.put(bridgeKey, SECRET);

				copy.put(key, bridge);
			}

		return copy;
	}

	@Test
	void theVelocitySampleLoadsCompletelyAndIsSafeByDefault() throws IOException {
		final ProxySettings settings = ProxySettings.parse(withSecret(load(VELOCITY)));

		assertEquals("velocity-1", settings.nodeId());
		assertEquals(Zone.PUBLIC, settings.database().zones().use());
		assertEquals("authenticator", settings.database().database());
		assertEquals(BridgeMode.PLUGIN_MESSAGING, settings.bridge().mode(), "PluginMessaging is the default: no extra service to expose");
		assertEquals(PremiumPolicy.PROTECT, settings.premium().policy());
		assertEquals(HashAlgo.BCRYPT, settings.passwords().hasher().algorithm());
		assertEquals(12, settings.passwords().hasher().bcryptCost());
		assertEquals(List.of("auth"), settings.routing().authServers());
		assertEquals(List.of("lobby"), settings.routing().lobbyServers());
		assertEquals(false, settings.dryRun(), "shadow mode must be off by default");
		assertEquals("mineLogin", settings.minelogin().table());
		assertEquals("`mineLogin`", settings.minelogin().qualifiedName());
		assertTrue(settings.login().nickname().matcher("Steve_01").matches());
		assertEquals(90, settings.auditRetentionDays());
	}

	@Test
	void theVelocitySampleWithoutASecretIsRefused() throws IOException {
		final ConfigException ex = assertThrows(ConfigException.class, () -> ProxySettings.parse(load(VELOCITY)));

		assertTrue(ex.getMessage().contains("secret"), ex.getMessage());
	}

	@Test
	void theVelocitySampleRedisSectionLoadsWhenRedisIsChosen() throws IOException {
		final Map<String, Object> root = withSecret(load(VELOCITY));

		@SuppressWarnings("unchecked")
		final Map<String, Object> bridge = new LinkedHashMap<>((Map<String, Object>) root.get("bridge"));
		bridge.put("mode", "redis");
		root.put("bridge", bridge);

		final RedisConfig redis = ProxySettings.parse(root).bridge().redis();

		assertEquals(Zone.PUBLIC, redis.zones().use());
		assertTrue(redis.zones().endpoints().get(Zone.PUBLIC).ssl());
		assertEquals(2, redis.zones().endpoints().size());
	}

	@Test
	void thePaperSampleLoadsAndUsesThePrivateEndpoints() throws IOException {
		final Map<String, Object> root = withSecret(load(PAPER));

		assertNotNull(root.get("Node_Id"));

		final DatabaseConfig database = DatabaseConfig.parse(section(root, "Database"), Zone.PUBLIC);
		final BridgeConfig bridge = BridgeConfig.parse(root, Zone.PUBLIC);

		assertEquals(Zone.PRIVATE, database.zones().use());
		assertEquals(2, database.zones().endpoints().size());
		assertEquals(BridgeMode.PLUGIN_MESSAGING, bridge.mode());
	}

	@Test
	void thePaperSampleRedisSectionLoadsWhenRedisIsChosen() throws IOException {
		final Map<String, Object> root = withSecret(load(PAPER));

		@SuppressWarnings("unchecked")
		final Map<String, Object> bridge = new LinkedHashMap<>((Map<String, Object>) root.get("Bridge"));
		bridge.put("Mode", "redis");
		root.put("Bridge", bridge);

		final RedisConfig redis = BridgeConfig.parse(root, Zone.PUBLIC).redis();

		assertEquals(Zone.PRIVATE, redis.zones().use());
		assertEquals(2, redis.zones().endpoints().size());
	}

	@Test
	void bothSamplesDescribeTheSameDatabaseAndBridge() throws IOException {
		final ProxySettings proxy = ProxySettings.parse(withSecret(load(VELOCITY)));
		final DatabaseConfig backend = DatabaseConfig.parse(section(load(PAPER), "Database"), Zone.PUBLIC);

		assertEquals(proxy.database().database(), backend.database());

		for (final Zone zone : Zone.values()) {
			final DatabaseEndpoint a = proxy.database().zones().endpoints().get(zone);
			final DatabaseEndpoint b = backend.zones().endpoints().get(zone);

			assertEquals(a.host(), b.host(), zone + " host differs between the two samples");
			assertEquals(a.port(), b.port());
			assertEquals(a.sslMode(), b.sslMode());
		}

		assertEquals(proxy.bridge().mode(), BridgeConfig.parse(withSecret(load(PAPER)), Zone.PRIVATE).mode());
		assertEquals(proxy.bridge().maxSkewSeconds(), BridgeConfig.parse(withSecret(load(PAPER)), Zone.PRIVATE).maxSkewSeconds());
	}

	@Test
	void thePaperSampleWithoutASecretIsRefused() throws IOException {
		assertThrows(ConfigException.class, () -> BridgeConfig.parse(load(PAPER), Zone.PRIVATE));
	}
}
