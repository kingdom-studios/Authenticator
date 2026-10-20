package pl.kingdomcraft.authenticator.paper.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.settings.BackendSettings;

/** The files the plugin ships must load the way the plugin loads them. */
class ShippedFilesTest {

	private static final Path RESOURCES = Path.of("src", "main", "resources");
	private static final Path SETTINGS = RESOURCES.resolve("settings.yml");

	@SuppressWarnings("unchecked")
	private static Map<String, Object> yaml(Path file) throws IOException {
		return (Map<String, Object>) new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private static Object at(Map<String, Object> root, String path) {
		Object current = root;

		for (final String key : path.split("\\.")) {
			assertTrue(current instanceof Map, path + ": '" + key + "' is not inside a section");
			current = ((Map<String, Object>) current).get(key);
			assertNotNull(current, "missing in the file: " + path);
		}

		return current;
	}

	// ---- settings.yml ----

	@Test
	void theShippedSettingsAreRefusedUntilASecretIsChosen(@TempDir Path directory) throws IOException {
		final Path copy = directory.resolve("settings.yml");

		Files.copy(SETTINGS, copy);

		final ConfigException ex = assertThrows(ConfigException.class, () -> SettingsFile.load(copy));

		assertTrue(ex.getMessage().toLowerCase().contains("secret"), ex.getMessage());
	}

	@Test
	void theShippedSettingsLoadOnceASecretIsSet(@TempDir Path directory) throws IOException {
		final Path copy = directory.resolve("settings.yml");

		Files.writeString(copy, Files.readString(SETTINGS, StandardCharsets.UTF_8).replace("Secret: \"\"", "Secret: \"0123456789abcdef-test-secret\""), StandardCharsets.UTF_8);

		final BackendSettings settings = SettingsFile.load(copy);

		assertEquals("paper-1", settings.nodeId());
		assertEquals(Zone.PRIVATE, settings.database().zones().use());
		assertEquals(BridgeMode.PLUGIN_MESSAGING, settings.bridge().mode());
	}

	@Test
	void anEmptyFileIsRefusedWithAMessageThatSaysWhatToDo(@TempDir Path directory) throws IOException {
		final Path empty = directory.resolve("settings.yml");

		Files.writeString(empty, "");

		assertTrue(assertThrows(ConfigException.class, () -> SettingsFile.load(empty)).getMessage().contains("empty"));
	}

	@Test
	void everyKeyFoundationReadsIsInTheShippedFile() throws IOException {
		final Map<String, Object> root = yaml(SETTINGS);

		for (final String key : new String[] { "Server.Role", "Gate.Poll_Seconds", "Gate.Hide_Unauthenticated", "Gate.Message_Cooldown_Seconds", "Login_Spawn.World", "Login_Spawn.X", "Login_Spawn.Y",
				"Login_Spawn.Z", "Login_Spawn.Yaw", "Login_Spawn.Pitch", "Locale", "Command_Aliases", "Node_Id" })
			at(root, key);
	}

	@Test
	void theShippedDefaultsAreTheStrictOnes() throws IOException {
		final Map<String, Object> root = yaml(SETTINGS);

		assertEquals("GAME", at(root, "Server.Role"));
		assertEquals(Boolean.TRUE, at(root, "Gate.Hide_Unauthenticated"));
		assertEquals("", at(root, "Login_Spawn.World"));
		assertEquals("pl", at(root, "Locale"));
	}

	@Test
	void theCommandIsNotCalledAuthadminBecauseTheProxyHasThatOne() throws IOException {
		assertFalse(((java.util.List<?>) at(yaml(SETTINGS), "Command_Aliases")).contains("authadmin"));
	}

	// ---- localization ----

	private static final Path EN = RESOURCES.resolve("localization").resolve("messages_en.yml");
	private static final Path PL = RESOURCES.resolve("localization").resolve("messages_pl.yml");

	/** The key of a field in the file: {@code LOGIN_FIRST} is {@code Login_First}. */
	private static String keyOf(String fieldName) {
		final StringBuilder key = new StringBuilder();

		for (final String word : fieldName.toLowerCase().split("_"))
			key.append(key.length() == 0 ? "" : "_").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));

		return key.toString();
	}

	@Test
	void everyMessageTheCodeHasIsInBothLanguageFilesAndTheEnglishOneSaysTheSame() throws Exception {
		final Map<String, Object> en = yaml(EN);
		final Map<String, Object> pl = yaml(PL);
		int checked = 0;

		for (final Class<?> section : new Class<?>[] { Localization.Gate.class, Localization.Admin.class, Localization.Placeholders.class }) {
			for (final Field field : section.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class)
					continue;

				final String path = section.getSimpleName() + "." + keyOf(field.getName());

				assertEquals(field.get(null), at(en, path), path + ": the English file and the default in the code must agree");
				assertNotNull(at(pl, path));

				checked++;
			}
		}

		assertTrue(checked > 40, "expected to check the plugin's messages, checked " + checked);
	}

	@SuppressWarnings("unchecked")
	@Test
	void bothLanguageFilesHaveTheSameAuthenticatorKeys() throws IOException {
		for (final String section : new String[] { "Gate", "Admin", "Placeholders" })
			assertEquals(new TreeSet<>(((Map<String, Object>) at(yaml(EN), section)).keySet()), new TreeSet<>(((Map<String, Object>) at(yaml(PL), section)).keySet()), section);
	}

	@SuppressWarnings("unchecked")
	@Test
	void everyPlaceholderInTheMessagesIsOneTheCodeFills() throws IOException {
		final java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{(\\w+)\\}");
		final java.util.Set<String> known = java.util.Set.of("player", "min", "key", "value", "line", "example", "error");

		for (final Path file : new Path[] { EN, PL })
			for (final String section : new String[] { "Gate", "Admin", "Placeholders" })
				for (final Map.Entry<String, Object> entry : ((Map<String, Object>) at(yaml(file), section)).entrySet()) {
					final java.util.regex.Matcher matcher = placeholder.matcher(String.valueOf(entry.getValue()));

					while (matcher.find())
						assertTrue(known.contains(matcher.group(1)), file.getFileName() + " " + section + "." + entry.getKey() + ": unknown placeholder {" + matcher.group(1) + "}");
				}
	}

	@Test
	void polishDiacriticsSurviveInTheFile() throws IOException {
		assertTrue(Files.readString(PL, StandardCharsets.UTF_8).contains("Najpierw się zaloguj"));
	}

	@Test
	void anOldSettingsFileIsBroughtUpToDateBeforeFoundationReadsIt(@TempDir Path directory) throws IOException {
		final Path file = directory.resolve("settings.yml");
		final java.util.List<String> log = new java.util.ArrayList<>();

		Files.writeString(file, Files.readString(SETTINGS, StandardCharsets.UTF_8).replace("Secret: \"\"", "Secret: \"0123456789abcdef-test-secret\"").replace("      Server_Ssl_Cert: \"\"\n", ""), StandardCharsets.UTF_8);

		SettingsFile.update(directory, ShippedFilesTest.class.getResourceAsStream("/settings.yml"), log::add, log::add);

		final String updated = Files.readString(file, StandardCharsets.UTF_8);

		assertTrue(updated.contains("Server_Ssl_Cert: \"\""), log.toString());
		assertTrue(updated.contains("Secret: \"0123456789abcdef-test-secret\""));
		assertTrue(Files.exists(directory.resolve("settings.yml.bak")));
		assertEquals("paper-1", SettingsFile.load(file).nodeId());

		SettingsFile.update(directory, null, log::add, log::add); // no bundled file: nothing happens, nothing throws
		SettingsFile.update(directory.resolve("nowhere"), ShippedFilesTest.class.getResourceAsStream("/settings.yml"), log::add, log::add);
	}
}
