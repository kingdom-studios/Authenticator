package pl.kingdomcraft.authenticator.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import pl.kingdomcraft.authenticator.common.settings.BackendSettings;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;

class ConfigUpdaterTest {

	private static final Path VELOCITY = Path.of("..", "authenticator-velocity", "src", "main", "resources", "config.yml");
	private static final Path PAPER = Path.of("..", "authenticator-paper", "src", "main", "resources", "settings.yml");

	@TempDir
	Path dir;

	private final List<String> log = new ArrayList<>();

	private ConfigUpdater.Result update(Path file, String template, String versionKey) throws IOException {
		return ConfigUpdater.update(file, template, versionKey, this.log::add);
	}

	/** The shipped sample as an older version wrote it: without the certificate key and its comment, and without the version. */
	private static String older(Path sample, String certificateKey, String versionKey) throws IOException {
		final List<String> out = new ArrayList<>();
		boolean skipping = false;

		for (final String line : Files.readAllLines(sample)) {
			if (line.startsWith("#   " + certificateKey))
				skipping = true;
			else if (skipping && (line.startsWith("#   properties") || line.startsWith("#   Properties")))
				skipping = false;

			if (skipping || line.strip().startsWith(certificateKey + ":") || line.startsWith(versionKey + ":"))
				continue;

			out.add(line);
		}

		return String.join("\n", out) + "\n";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(String text) {
		try (Reader reader = new StringReader(text)) {
			return (Map<String, Object>) new Yaml().load(reader);
		} catch (final IOException ex) {
			throw new AssertionError(ex);
		}
	}

	/** Every value in {@code old} is in {@code updated} at the same place (keys compared the way the updater does). */
	@SuppressWarnings("unchecked")
	private static void assertKept(Map<String, Object> old, Map<String, Object> updated, String path) {
		for (final Map.Entry<String, Object> entry : old.entrySet()) {
			final String norm = entry.getKey().toLowerCase().replace("-", "").replace("_", "");
			Object found = null;

			for (final Map.Entry<String, Object> candidate : updated.entrySet())
				if (candidate.getKey().toLowerCase().replace("-", "").replace("_", "").equals(norm))
					found = candidate.getValue();

			assertTrue(found != null || entry.getValue() == null, path + entry.getKey() + " is gone");

			if (entry.getValue() instanceof Map<?, ?> inner && inner.keySet().stream().allMatch(String.class::isInstance) && found instanceof Map<?, ?>)
				assertKept((Map<String, Object>) entry.getValue(), (Map<String, Object>) found, path + entry.getKey() + ".");
			else
				assertEquals(entry.getValue(), found, path + entry.getKey());
		}
	}

	// ---------------------------------------------------------------- the shipped files, from an older version

	@Test
	void anOldVelocityConfigGetsTheNewKeyAndKeepsEverythingTheAdminSet() throws Exception {
		String old = older(VELOCITY, "server-ssl-cert", "config-version");

		old = old.replace("host: db.example.com", "host: db.devkdc.pl")
				.replaceFirst("password: \"\"", "password: \"p@ss w0rd # not a comment\"")
				.replaceFirst("(?m)^  secret: \"\"", "  secret: \"0123456789abcdef-secret\"")
				.replaceFirst("(?m)^      properties: \\{\\}", "      properties:\n        serverSslCert: devkdc-db-ca.pem\n        # my own option\n        tcpKeepAlive: \"true\"")
				.replace("auth-servers: [auth]", "auth-servers:\n    - auth-1\n    - auth-2")
				.replace("messages: {}", "messages:\n  # welcome text\n  login.ok: \"<green>Witaj ponownie!\"\n  register.ok: \"Konto gotowe\"");

		assertFalse(old.contains("server-ssl-cert:") || old.contains("config-version:"));

		Files.writeString(this.dir.resolve("devkdc-db-ca.pem"), "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");

		final Path file = Files.writeString(this.dir.resolve("config.yml"), old);
		final String template = Files.readString(VELOCITY);
		final ConfigUpdater.Result result = this.update(file, template, "config-version");

		assertEquals(ConfigUpdater.Status.UPDATED, result.status());
		assertTrue(result.added().contains("config-version"), result.added().toString());
		assertTrue(result.added().stream().anyMatch(key -> key.endsWith("server-ssl-cert")), result.added().toString());
		assertEquals(old, Files.readString(result.backup()), "the old file is saved as it was");
		assertEquals("config.yml.bak", result.backup().getFileName().toString());
		assertTrue(this.log.get(0).contains("server-ssl-cert") && this.log.get(0).contains("config.yml.bak"), this.log.toString());

		final String updated = Files.readString(file);

		// the new key arrives with its comment, in the template's place
		assertTrue(updated.contains("#   server-ssl-cert - optional."), "the comment of the new key");
		assertEquals(2, updated.split("server-ssl-cert: \"\"", -1).length - 1, "once per endpoint");
		assertTrue(updated.contains("config-version: 1"));

		// what the admin set is still there, including the lines the template has no idea about
		assertTrue(updated.contains("password: \"p@ss w0rd # not a comment\""));
		assertTrue(updated.contains("host: db.devkdc.pl"));
		assertTrue(updated.contains("secret: \"0123456789abcdef-secret\""));
		assertTrue(updated.contains("        serverSslCert: devkdc-db-ca.pem\n        # my own option\n        tcpKeepAlive: \"true\""), updated);
		assertTrue(updated.contains("    - auth-1\n    - auth-2"));
		assertTrue(updated.contains("messages:\n  # welcome text\n  login.ok: \"<green>Witaj ponownie!\"\n  register.ok: \"Konto gotowe\""));
		assertKept(parse(old), parse(updated), "");

		// and it is a config the plugin reads
		final ProxySettings settings = ProxySettings.parse(parse(updated), this.dir);

		assertEquals(List.of("auth-1", "auth-2"), settings.routing().authServers());
		assertEquals("Konto gotowe", settings.messages().get("register.ok"));
		assertEquals("db.devkdc.pl", settings.database().zones().endpoints().get(Zone.PUBLIC).host());
		assertEquals(this.dir.resolve("devkdc-db-ca.pem").toUri().toString(), settings.database().zones().endpoints().get(Zone.PUBLIC).properties().get("serverSslCert"),
				"a certificate the admin already had in properties is still used");
	}

	@Test
	void anOldPaperSettingsFileGetsTheNewKeyAndKeepsEverythingTheAdminSet() throws Exception {
		String old = older(PAPER, "Server_Ssl_Cert", "Version");

		old = old.replace("Node_Id: \"paper-1\"", "Node_Id: \"lobby-7\"")
				.replace("Role: GAME", "Role: AUTH")
				.replace("Host: \"db.example.com\"", "Host: \"db.devkdc.pl\"")
				.replaceFirst("Password: \"\"", "Password: \"s3cret!\"")
				.replaceFirst("(?m)^  Secret: \"\"", "  Secret: \"0123456789abcdef-secret\"")
				.replaceFirst("(?m)^      Properties: \\{\\}", "      Properties:\n        tcpKeepAlive: \"true\"")
				.replace("Command_Aliases: [auth, authenticator]", "Command_Aliases: [auth]");

		assertFalse(old.contains("Server_Ssl_Cert:") || old.contains("\nVersion:"));

		final Path file = Files.writeString(this.dir.resolve("settings.yml"), old);
		final ConfigUpdater.Result result = this.update(file, Files.readString(PAPER), "Version");
		final String updated = Files.readString(file);

		assertEquals(ConfigUpdater.Status.UPDATED, result.status());
		assertTrue(result.added().stream().anyMatch(key -> key.endsWith("Server_Ssl_Cert")), result.added().toString());
		assertTrue(updated.contains("#   Server_Ssl_Cert - optional."));
		assertEquals(2, updated.split("Server_Ssl_Cert: \"\"", -1).length - 1);
		assertTrue(updated.contains("Node_Id: \"lobby-7\"") && updated.contains("Password: \"s3cret!\"") && updated.contains("Command_Aliases: [auth]"));
		assertTrue(updated.contains("      Properties:\n        tcpKeepAlive: \"true\""));
		assertKept(parse(old), parse(updated), "");

		final BackendSettings settings = BackendSettings.parse(parse(updated), this.dir);

		assertEquals("lobby-7", settings.nodeId());
		assertEquals("db.devkdc.pl", settings.database().zones().endpoints().get(Zone.PUBLIC).host());
		assertEquals("true", settings.database().zones().endpoints().get(Zone.PRIVATE).properties().get("tcpKeepAlive"));
	}

	@Test
	void aCurrentFileIsNotTouchedAndNoBackupIsMade() throws Exception {
		for (final Object[] sample : new Object[][] { { VELOCITY, "config-version" }, { PAPER, "Version" } }) {
			final Path file = this.dir.resolve(((Path) sample[0]).getFileName().toString());
			final String text = Files.readString((Path) sample[0]).replace("secret: \"\"", "secret: \"changed\"").replace("Secret: \"\"", "Secret: \"changed\"");

			Files.writeString(file, text);

			final long modified = Files.getLastModifiedTime(file).toMillis();
			final ConfigUpdater.Result result = this.update(file, Files.readString((Path) sample[0]), (String) sample[1]);

			assertEquals(ConfigUpdater.Status.UNCHANGED, result.status());
			assertEquals(text, Files.readString(file));
			assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
			assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".bak")));
		}

		assertTrue(this.log.isEmpty(), this.log.toString());
	}

	@Test
	void updatingTwiceChangesNothingTheSecondTime() throws Exception {
		final Path file = Files.writeString(this.dir.resolve("config.yml"), older(VELOCITY, "server-ssl-cert", "config-version"));
		final String template = Files.readString(VELOCITY);

		assertEquals(ConfigUpdater.Status.UPDATED, this.update(file, template, "config-version").status());

		final String once = Files.readString(file);

		assertEquals(ConfigUpdater.Status.UNCHANGED, this.update(file, template, "config-version").status());
		assertEquals(once, Files.readString(file));
		assertFalse(Files.exists(this.dir.resolve("config.yml.bak.1")));
	}

	// ---------------------------------------------------------------- the rules, on small files

	private static final String TEMPLATE = """
			# header
			version: 2

			# the node
			node: a   # inline comment

			# the database
			db:
			  # the host
			  host: h
			  # new key
			  cert: ""
			  props: {}
			  list: []

			# last
			tail: 1
			""";

	@Test
	void aValueIsCarriedToTheTemplatePlaceEvenWhenTheOrderAndIndentationDiffer() throws Exception {
		final Path file = Files.writeString(this.dir.resolve("c.yml"), """
				version: 1
				tail: 9
				DB:
				    HOST: other
				    props:
				        x: 1
				        # note
				        y: [1, 2]
				    list:
				    - a
				    - b
				node: "mine"
				""");

		final ConfigUpdater.Result result = this.update(file, TEMPLATE, "version");
		final String updated = Files.readString(file);

		assertEquals("""
				# header
				version: 2

				# the node
				node: "mine"

				# the database
				db:
				  # the host
				  host: other
				  # new key
				  cert: ""
				  props:
				      x: 1
				      # note
				      y: [1, 2]
				  list:
				  - a
				  - b

				# last
				tail: 9
				""".replace("  props:\n      x", "  props:\n      x"), updated);
		assertEquals(List.of("db.cert"), result.added());
		assertEquals(Map.of("version", 2, "node", "mine", "tail", 9), Map.of("version", parse(updated).get("version"), "node", parse(updated).get("node"), "tail", parse(updated).get("tail")));
	}

	@Test
	void aKeyThatIsGoneIsLoggedAndStaysInTheBackup() throws Exception {
		final Path file = Files.writeString(this.dir.resolve("c.yml"), "version: 1\nnode: x\nretired: 5\ndb:\n  host: h\n  old-option: true\n");

		final ConfigUpdater.Result result = this.update(file, TEMPLATE, "version");

		assertEquals(List.of("retired", "db.old-option"), result.dropped());
		assertFalse(Files.readString(file).contains("retired"));
		assertTrue(Files.readString(result.backup()).contains("retired: 5"));
		assertTrue(this.log.stream().anyMatch(line -> line.contains("retired") && line.contains("db.old-option")), this.log.toString());
	}

	@Test
	void aNewerVersionNumberAloneTriggersAnUpdateAndAnOlderTemplateDoesNot() throws Exception {
		final String same = "version: 1\nnode: x\ndb:\n  host: h\n  cert: \"\"\n  props: {}\n  list: []\ntail: 1\n";
		final Path file = Files.writeString(this.dir.resolve("c.yml"), same);

		assertEquals(ConfigUpdater.Status.UPDATED, this.update(file, TEMPLATE, "version").status(), "version 1 < 2");
		assertTrue(Files.readString(file).contains("version: 2"));

		final Path newer = Files.writeString(this.dir.resolve("n.yml"), same.replace("version: 1", "version: 3"));

		assertEquals(ConfigUpdater.Status.UNCHANGED, this.update(newer, TEMPLATE, "version").status(), "a file from a newer version is left alone");
	}

	@Test
	void aSecondBackupDoesNotOverwriteTheFirst() throws Exception {
		final Path file = this.dir.resolve("c.yml");

		Files.writeString(file, "version: 1\nnode: first\n");
		this.update(file, TEMPLATE, "version");

		Files.writeString(file, "version: 1\nnode: second\n");
		final ConfigUpdater.Result second = this.update(file, TEMPLATE, "version");

		assertEquals("c.yml.bak.1", second.backup().getFileName().toString());
		assertTrue(Files.readString(this.dir.resolve("c.yml.bak")).contains("first"));
		assertTrue(Files.readString(second.backup()).contains("second"));
	}

	@Test
	void aMissingEmptyOrUnreadableFileIsLeftToTheParser() throws Exception {
		assertEquals(ConfigUpdater.Status.UNCHANGED, this.update(this.dir.resolve("none.yml"), TEMPLATE, "version").status());

		final Path empty = Files.writeString(this.dir.resolve("e.yml"), "");

		assertEquals(ConfigUpdater.Status.UNCHANGED, this.update(empty, TEMPLATE, "version").status());
		assertEquals("", Files.readString(empty));

		final Path flow = Files.writeString(this.dir.resolve("f.yml"), "{node: x, db: {host: h}}\n");

		assertEquals(ConfigUpdater.Status.UNCHANGED, this.update(flow, TEMPLATE, "version").status());
		assertEquals("{node: x, db: {host: h}}\n", Files.readString(flow));
	}

	@Test
	void keysAreMatchedIgnoringCaseHyphenAndUnderscoreAndTheTemplateSpellingIsWritten() throws Exception {
		final Path file = Files.writeString(this.dir.resolve("c.yml"), "version: 2\nnode: a\ndb:\n  host: h\n  cert: \"\"\n  props: {}\n  list: []\nTAIL: 7\n");

		assertEquals(ConfigUpdater.Status.UNCHANGED, this.update(file, TEMPLATE.replace("tail: 1", "ta_il: 1"), "version").status(), "TAIL is ta_il: nothing is missing");

		Files.writeString(file, "version: 1\nnode: a\ndb:\n  host: h\n  cert: \"\"\n  props: {}\n  list: []\nTAIL: 7\n");
		this.update(file, TEMPLATE.replace("tail: 1", "ta-il: 1"), "version");

		assertTrue(Files.readString(file).contains("\nta-il: 7"), Files.readString(file));
	}

	@Test
	void aValueIndentedLessThanTheTemplateIsMovedIn() throws Exception {
		final Path file = Files.writeString(this.dir.resolve("c.yml"), "version: 1\ndb:\n host: h\n props:\n  a: 1\n");

		this.update(file, TEMPLATE, "version");

		assertTrue(Files.readString(file).contains("\n  props:\n   a: 1\n"), Files.readString(file));
		assertEquals(1, ((Map<?, ?>) ((Map<?, ?>) parse(Files.readString(file)).get("db")).get("props")).get("a"));
	}

	@Test
	void windowsLineEndingsAreFine() throws Exception {
		final Path file = Files.writeString(this.dir.resolve("c.yml"), "version: 1\r\nnode: crlf\r\ndb:\r\n  host: h\r\n");

		this.update(file, TEMPLATE, "version");

		assertEquals("crlf", parse(Files.readString(file)).get("node"));
	}
}
