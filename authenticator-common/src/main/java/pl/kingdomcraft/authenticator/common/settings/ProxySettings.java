package pl.kingdomcraft.authenticator.common.settings;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import pl.kingdomcraft.authenticator.common.bridge.BridgeConfig;
import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.database.DatabaseConfig;
import pl.kingdomcraft.authenticator.common.message.Messages;

/**
 * Everything the proxy reads from {@code config.yml}, validated. Parsing lives here, not in the Velocity module, so it is the same code
 * the tests run against the sample file.
 *
 * @param dryRun       shadow mode: decide and compare with another login plugin, but never change a connection (see {@code ShadowComparator})
 * @param auditRetention how many days audit entries are kept; 0 keeps them for ever
 * @param minelogin    where the mineLogin table to import from is
 * @param language     {@code pl} or {@code en}: the language of the built-in messages
 * @param messages     texts that replace the built-in ones, by key
 */
public record ProxySettings(String nodeId, DatabaseConfig database, BridgeConfig bridge, LoginSettings login, RoutingSettings routing, PremiumSettings premium, LimitSettings limits,
		PasswordSettings passwords, int auditRetentionDays, MineLoginSource minelogin, boolean dryRun, String language, Map<String, String> messages) {

	private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]{1,64}");

	/**
	 * @param schema the database the mineLogin table is in; empty for the one Authenticator uses
	 * @param table  its name
	 */
	public record MineLoginSource(String schema, String table) {

		public MineLoginSource {
			Objects.requireNonNull(schema, "schema");

			if (!schema.isEmpty() && !IDENTIFIER.matcher(schema).matches())
				throw new IllegalArgumentException("schema '" + schema + "' is not a plain database name");

			if (!IDENTIFIER.matcher(table).matches())
				throw new IllegalArgumentException("table '" + table + "' is not a plain table name");
		}

		/** The table as it is written in a query; both parts passed the identifier check, so quoting is only for reserved words. */
		public String qualifiedName() {
			return (this.schema.isEmpty() ? "" : "`" + this.schema + "`.") + "`" + this.table + "`";
		}
	}

	public ProxySettings {
		if (!NODE_ID.matcher(nodeId).matches())
			throw new IllegalArgumentException("node-id must be 1-64 letters, digits, '.', '_' or '-'");

		if (auditRetentionDays < 0)
			throw new IllegalArgumentException("retention-days cannot be negative");

		if (!Messages.LANGUAGES.contains(language))
			throw new IllegalArgumentException("language must be one of " + new java.util.TreeSet<>(Messages.LANGUAGES));

		messages = Map.copyOf(messages);
	}

	/** As {@link #parse(Map, Path)}, with relative certificate paths relative to the working directory (for tests; the plugin passes its data folder). */
	public static ProxySettings parse(Map<?, ?> root) {
		return parse(root, Path.of(""));
	}

	/**
	 * @param root          the whole {@code config.yml} as a map
	 * @param dataDirectory the plugin's data folder, which a relative certificate path is relative to
	 */
	public static ProxySettings parse(Map<?, ?> root, Path dataDirectory) {
		final ConfigSection section = ConfigSection.of("", root);
		final String nodeId = section.requireString("node-id");

		final ConfigSection database = section.section("database");

		if (database == null)
			throw section.error("database", "section is missing");

		final ConfigSection minelogin = section.section("migration") == null ? null : section.section("migration").section("minelogin");
		final ConfigSection audit = section.section("audit");

		try {
			final ProxySettings settings = new ProxySettings(nodeId, DatabaseConfig.parse(database, Zone.PUBLIC, dataDirectory), BridgeConfig.parse(section, Zone.PUBLIC), LoginSettings.parse(orEmpty(section, "login")),
					RoutingSettings.parse(orEmpty(section, "routing")), PremiumSettings.parse(orEmpty(section, "premium")), LimitSettings.parse(orEmpty(section, "limits")),
					PasswordSettings.parse(orEmpty(section, "passwords")), audit == null ? 90 : audit.integer("retention-days", 90),
					minelogin == null ? new MineLoginSource("", "mineLogin") : new MineLoginSource(minelogin.string("schema", ""), minelogin.string("table", "mineLogin")), section.bool("dry-run", false),
					section.string("language", "pl").trim().toLowerCase(java.util.Locale.ROOT), section.stringMap("messages"));

			Messages.load(settings.language(), settings.messages()); // unknown keys are mistakes: say so now

			return settings;

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(ex.getMessage(), ex);
		}
	}

	private static ConfigSection orEmpty(ConfigSection parent, String key) {
		final ConfigSection section = parent.section(key);

		return section != null ? section : ConfigSection.of(parent.path(key), Map.of());
	}
}
