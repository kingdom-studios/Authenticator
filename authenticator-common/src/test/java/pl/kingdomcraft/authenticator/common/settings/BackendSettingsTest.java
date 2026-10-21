package pl.kingdomcraft.authenticator.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;

class BackendSettingsTest {

	/** The smallest valid file, in the style of Foundation's settings.yml. */
	private static Map<String, Object> minimal() {
		final Map<String, Object> root = new LinkedHashMap<>();

		root.put("Node_Id", "paper-auth-1");
		root.put("Database", Map.of("Name", "authenticator", "Endpoints", Map.of("Private", Map.of("Host", "10.0.0.5", "User", "u", "Password", "p"))));
		root.put("Bridge", new LinkedHashMap<>(Map.of("Secret", "0123456789abcdef-test-secret")));

		return root;
	}

	@Test
	void aMinimalFileGetsTheDefaults() {
		final BackendSettings settings = BackendSettings.parse(minimal());

		assertEquals("paper-auth-1", settings.nodeId());
		assertEquals(Zone.PRIVATE, settings.database().zones().use(), "a backend server uses the private endpoint unless told otherwise");
		assertEquals(BridgeMode.PLUGIN_MESSAGING, settings.bridge().mode());
		assertEquals(HashAlgo.BCRYPT, settings.passwords().hasher().algorithm());
		assertEquals("`mineLogin`", settings.minelogin().qualifiedName());
		assertTrue(settings.premium().apis().contains("mojang"));
	}

	@Test
	void theAdminSectionsCanBeSet() {
		final Map<String, Object> root = minimal();

		root.put("Premium", Map.of("Apis", List.of("ashcon"), "Timeout_Ms", 5000));
		root.put("Passwords", Map.of("Algorithm", "argon2id", "Min_Length", 10));
		root.put("Migration", Map.of("Minelogin", Map.of("Schema", "old", "Table", "users")));

		final BackendSettings settings = BackendSettings.parse(root);

		assertEquals(List.of("ashcon"), settings.premium().apis());
		assertEquals(HashAlgo.ARGON2ID, settings.passwords().hasher().algorithm());
		assertEquals(10, settings.passwords().minLength());
		assertEquals("`old`.`users`", settings.minelogin().qualifiedName());
	}

	@Test
	void theBridgeNeedsASecretUnlessThereIsNone() {
		final Map<String, Object> noSecret = minimal();
		noSecret.put("Bridge", Map.of("Mode", "plugin-messaging"));

		assertThrows(ConfigException.class, () -> BackendSettings.parse(noSecret));

		final Map<String, Object> databaseOnly = minimal();
		databaseOnly.put("Bridge", Map.of("Mode", "database-only"));

		assertEquals(BridgeMode.DATABASE_ONLY, BackendSettings.parse(databaseOnly).bridge().mode());
	}

	@Test
	void mistakesAreRefusedByName() {
		final Map<String, Object> badNode = minimal();
		badNode.put("Node_Id", "paper auth");

		assertTrue(assertThrows(ConfigException.class, () -> BackendSettings.parse(badNode)).getMessage().contains("node-id"));

		final Map<String, Object> noDatabase = minimal();
		noDatabase.remove("Database");

		assertTrue(assertThrows(ConfigException.class, () -> BackendSettings.parse(noDatabase)).getMessage().contains("database"));

		final Map<String, Object> noNode = minimal();
		noNode.remove("Node_Id");

		assertTrue(assertThrows(ConfigException.class, () -> BackendSettings.parse(noNode)).getMessage().contains("node-id"));
	}
}
