package pl.kingdomcraft.authenticator.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.PremiumPolicy;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;

class ProxySettingsTest {

	/** The smallest config that is valid: everything else has a default. */
	private static Map<String, Object> minimal() {
		final Map<String, Object> root = new LinkedHashMap<>();

		root.put("node-id", "velocity-1");
		root.put("database", Map.of("name", "authenticator", "endpoints", Map.of("public", Map.of("host", "db.example.com", "user", "u", "password", "p"))));
		root.put("bridge", new LinkedHashMap<>(Map.of("secret", "0123456789abcdef-test-secret")));
		root.put("routing", Map.of("auth-servers", List.of("auth"), "lobby-servers", List.of("lobby")));

		return root;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> with(Map<String, Object> root, String section, String key, Object value) {
		final Map<String, Object> copy = new LinkedHashMap<>(root);
		final Map<String, Object> inner = new LinkedHashMap<>((Map<String, Object>) copy.getOrDefault(section, new LinkedHashMap<>()));

		inner.put(key, value);
		copy.put(section, inner);

		return copy;
	}

	private static ConfigException refused(Map<String, Object> root) {
		return assertThrows(ConfigException.class, () -> ProxySettings.parse(root));
	}

	@Test
	void aMinimalConfigGetsSafeDefaults() {
		final ProxySettings settings = ProxySettings.parse(minimal());

		assertEquals(BridgeMode.PLUGIN_MESSAGING, settings.bridge().mode());
		assertEquals(PremiumPolicy.PROTECT, settings.premium().policy());
		assertEquals(List.of("mojang", "ashcon", "minetools"), settings.premium().apis());
		assertEquals(HashAlgo.BCRYPT, settings.passwords().hasher().algorithm());
		assertEquals(Duration.ofSeconds(60), settings.login().timeout());
		assertEquals(3, settings.login().maxAttempts());
		assertEquals(Duration.ofMinutes(4320), settings.login().sessionDuration());
		assertTrue(settings.login().sessionsEnabled());
		assertEquals(5, settings.limits().connectionsPerIp());
		assertEquals(90, settings.auditRetentionDays());
		assertEquals(false, settings.dryRun());
		assertTrue(settings.messages().isEmpty());
		assertEquals("pl", settings.language());
		assertTrue(settings.passwords().forbidden().contains("password"));
	}

	@Test
	void theLanguageIsCheckedAndMessageKeysAreValidated() {
		final Map<String, Object> english = new LinkedHashMap<>(minimal());
		english.put("language", "EN");

		assertEquals("en", ProxySettings.parse(english).language());

		final Map<String, Object> klingon = new LinkedHashMap<>(minimal());
		klingon.put("language", "tlh");

		assertTrue(assertThrows(ConfigException.class, () -> ProxySettings.parse(klingon)).getMessage().contains("language"));
		assertTrue(refused(with(minimal(), "messages", "login-ok", "<green>typo in the key")).getMessage().contains("login-ok"));
	}

	@Test
	void everyValueCanBeOverridden() {
		Map<String, Object> root = minimal();

		root = with(root, "premium", "policy", "ALLOW_CLAIM_AUTO");
		root = with(root, "premium", "apis", List.of("ashcon"));
		root = with(root, "passwords", "algorithm", "argon2id");
		root = with(root, "passwords", "argon2", Map.of("memory-kib", 4096, "iterations", 3, "parallelism", 2));
		root = with(root, "passwords", "forbidden", List.of("Secret"));
		root = with(root, "login", "session-minutes", 0);
		root = with(root, "limits", "accounts-per-ip", 0);
		root = with(root, "audit", "retention-days", 0);
		root = with(root, "migration", "minelogin", Map.of("schema", "old_net", "table", "ml_users"));
		root = with(root, "messages", "login.ok", "<green>Welcome!");

		final Map<String, Object> copy = new LinkedHashMap<>(root);
		copy.put("dry-run", true);

		final ProxySettings settings = ProxySettings.parse(copy);

		assertEquals(PremiumPolicy.ALLOW_CLAIM_AUTO, settings.premium().policy());
		assertEquals(List.of("ashcon"), settings.premium().apis());
		assertEquals(HashAlgo.ARGON2ID, settings.passwords().hasher().algorithm());
		assertEquals(4096, settings.passwords().hasher().argonMemoryKib());
		assertEquals(3, settings.passwords().hasher().argonIterations());
		assertEquals(2, settings.passwords().hasher().argonParallelism());
		assertEquals(List.of("secret"), settings.passwords().forbidden(), "forbidden passwords are compared ignoring case");
		assertEquals(false, settings.login().sessionsEnabled());
		assertEquals(0, settings.limits().accountsPerIp());
		assertEquals(0, settings.auditRetentionDays());
		assertEquals("`old_net`.`ml_users`", settings.minelogin().qualifiedName());
		assertEquals("<green>Welcome!", settings.messages().get("login.ok"));
		assertTrue(settings.dryRun());
	}

	@Test
	void anInvalidValueNamesItsSection() {
		assertTrue(refused(with(minimal(), "login", "timeout-seconds", 1)).getMessage().startsWith("login:"));
		assertTrue(refused(with(minimal(), "limits", "connections-per-ip", 0)).getMessage().startsWith("limits:"));
		assertTrue(refused(with(minimal(), "passwords", "min-length", 0)).getMessage().startsWith("passwords:"));
		assertTrue(refused(with(minimal(), "passwords", "algorithm", "md5")).getMessage().contains("passwords.algorithm"));
		assertTrue(refused(with(minimal(), "premium", "policy", "ANYTHING_GOES")).getMessage().contains("premium.policy"));
		assertTrue(refused(with(minimal(), "premium", "apis", List.of("mojang", "bing"))).getMessage().startsWith("premium:"));
		assertTrue(refused(with(minimal(), "login", "nickname-regex", "([")).getMessage().contains("login.nickname-regex"));
	}

	@Test
	void unsafeOrBrokenValuesAreRefused() {
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "passwords", "bcrypt-cost", 3)));
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "passwords", "threads", 0)));
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "routing", "auth-servers", List.of())));
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "routing", "lobby-servers", List.of())));
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "limits", "ip-block-minutes", 0)));
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "login", "max-attempts", 0)));
		assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "premium", "timeout-ms", 50)));
	}

	@Test
	void theMineLoginTableNameCannotCarryAnInjection() {
		for (final Map<String, String> source : List.of(Map.of("table", "t; DROP TABLE auth_accounts"), Map.of("table", "a`b"), Map.of("schema", "x`.`y", "table", "t"), Map.of("table", ""),
				Map.of("schema", "a b", "table", "t")))
			assertThrows(ConfigException.class, () -> ProxySettings.parse(with(minimal(), "migration", "minelogin", source)), source.toString());
	}

	@Test
	void theNodeIdMustBeAPlainName() {
		for (final String bad : new String[] { "", "has space", "x".repeat(65), "semi;colon", "ünïcode" }) {
			final Map<String, Object> root = minimal();
			root.put("node-id", bad);

			assertThrows(ConfigException.class, () -> ProxySettings.parse(root), bad);
		}
	}

	@Test
	void requiredSectionsAndKeysMustBePresent() {
		for (final String key : new String[] { "node-id", "database", "bridge", "routing" }) {
			final Map<String, Object> root = minimal();
			root.remove(key);

			assertThrows(ConfigException.class, () -> ProxySettings.parse(root), "without " + key);
		}
	}

	@Test
	void databaseOnlyBridgeNeedsNoSecret() {
		final Map<String, Object> root = minimal();
		root.put("bridge", Map.of("mode", "database-only"));

		assertEquals(BridgeMode.DATABASE_ONLY, ProxySettings.parse(root).bridge().mode());
	}

	@Test
	void thePublicDatabaseEndpointIsHeldToTheTlsRule() {
		final Map<String, Object> root = minimal();
		root.put("database", Map.of("name", "authenticator", "endpoints", Map.of("public", Map.of("host", "db.example.com", "user", "u", "ssl-mode", "disable"))));

		assertThrows(ConfigException.class, () -> ProxySettings.parse(root));
	}
}
