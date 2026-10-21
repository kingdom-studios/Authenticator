package pl.kingdomcraft.authenticator.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;

/** The shipped config.yml, read through Configurate exactly as the plugin reads it. */
class ConfigLoaderTest {

	private static final Path SHIPPED = Path.of("src", "main", "resources", "config.yml");

	@Test
	void theShippedFileIsRefusedUntilASecretIsChosen(@TempDir Path directory) throws IOException {
		final Path copy = directory.resolve("config.yml");

		Files.copy(SHIPPED, copy);

		assertTrue(assertThrows(ConfigException.class, () -> ConfigLoader.load(copy)).getMessage().toLowerCase().contains("secret"));
	}

	@Test
	void theShippedFileLoadsOnceASecretIsSet(@TempDir Path directory) throws IOException {
		final Path copy = directory.resolve("config.yml");

		Files.writeString(copy, Files.readString(SHIPPED, StandardCharsets.UTF_8).replace("secret: \"\"", "secret: \"0123456789abcdef-test-secret\""), StandardCharsets.UTF_8);

		final ProxySettings settings = ConfigLoader.load(copy);

		assertEquals("velocity-1", settings.nodeId());
		assertEquals(Zone.PUBLIC, settings.database().zones().use());
		assertEquals(BridgeMode.PLUGIN_MESSAGING, settings.bridge().mode());
		assertEquals("pl", settings.language());
		assertEquals(false, settings.dryRun());
		assertEquals(java.util.List.of("auth"), settings.routing().authServers());
	}

	@Test
	void numbersBooleansListsAndEmptyCollectionsSurviveTheTrip(@TempDir Path directory) throws IOException {
		final Path copy = directory.resolve("config.yml");

		Files.writeString(copy, Files.readString(SHIPPED, StandardCharsets.UTF_8).replace("secret: \"\"", "secret: \"0123456789abcdef-test-secret\"").replace("dry-run: false", "dry-run: true")
				.replace("max-attempts: 3", "max-attempts: 5").replace("forbidden: [123456, 12345678, password, qwerty, 111111, abc123, letmein]", "forbidden: [Secret]"), StandardCharsets.UTF_8);

		final ProxySettings settings = ConfigLoader.load(copy);

		assertTrue(settings.dryRun());
		assertEquals(5, settings.login().maxAttempts());
		assertEquals(java.util.List.of("secret"), settings.passwords().forbidden());
		assertTrue(settings.messages().isEmpty(), "an empty map stays an empty map");
	}

	@Test
	void anEmptyOrBrokenFileIsRefusedWithAMessageThatSaysWhatToDo(@TempDir Path directory) throws IOException {
		final Path empty = directory.resolve("empty.yml");
		final Path broken = directory.resolve("broken.yml");

		Files.writeString(empty, "");
		Files.writeString(broken, "database: [unclosed\n  - x: : :");

		assertTrue(assertThrows(ConfigException.class, () -> ConfigLoader.load(empty)).getMessage().contains("empty"));
		assertTrue(assertThrows(ConfigException.class, () -> ConfigLoader.load(broken)).getMessage().contains("not valid YAML"));
	}

	@Test
	void theDefaultIsCopiedOnlyWhenThereIsNone(@TempDir Path directory) throws IOException {
		ConfigLoader.ensureDefault(directory, "config.yml");

		assertTrue(Files.exists(directory.resolve("config.yml")));

		Files.writeString(directory.resolve("config.yml"), "mine: true\n");
		ConfigLoader.ensureDefault(directory, "config.yml");

		assertEquals("mine: true\n", Files.readString(directory.resolve("config.yml")), "an admin's file is never overwritten");
	}

	@Test
	void anOldConfigIsBroughtUpToDateBeforeItIsRead(@TempDir Path directory) throws IOException {
		final Path file = directory.resolve("config.yml");
		final java.util.List<String> log = new java.util.ArrayList<>();

		Files.writeString(file, Files.readString(SHIPPED, StandardCharsets.UTF_8).replace("secret: \"\"", "secret: \"0123456789abcdef-test-secret\"").replace("config-version: 1\n", "").replace("      server-ssl-cert: \"\"\n", ""), StandardCharsets.UTF_8);

		ConfigLoader.update(directory, "config.yml", log::add);

		final String updated = Files.readString(file, StandardCharsets.UTF_8);

		assertTrue(updated.contains("config-version: 1") && updated.contains("server-ssl-cert: \"\""));
		assertTrue(updated.contains("secret: \"0123456789abcdef-test-secret\""));
		assertTrue(Files.exists(directory.resolve("config.yml.bak")));
		assertEquals(1, log.stream().filter(line -> line.contains("config.yml.bak")).count(), log.toString());
		assertEquals("0123456789abcdef-test-secret".length(), ConfigLoader.load(file).bridge().secret().length());

		ConfigLoader.update(directory, "config.yml", log::add);

		assertEquals(updated, Files.readString(file, StandardCharsets.UTF_8));
	}
}
