package pl.kingdomcraft.authenticator.common.settings;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import pl.kingdomcraft.authenticator.common.bridge.BridgeConfig;
import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.database.DatabaseConfig;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings.MineLoginSource;

/**
 * What a backend (Paper) server reads from {@code settings.yml} that is shared with the proxy's way of configuring things: the node, the
 * database, the bridge and what the admin commands need. Parsing lives here, not in the Paper module, so the sample file is checked by the
 * same code that runs in production. Settings only Paper has (the role, the spawn, the gate) are read by Foundation.
 *
 * @param premium   the lookup services {@code /authadmin setpremium} asks
 * @param passwords how a password set by an admin is hashed and what it must satisfy
 * @param minelogin where {@code /authadmin migrate minelogin} reads from
 */
public record BackendSettings(String nodeId, DatabaseConfig database, BridgeConfig bridge, PremiumSettings premium, PasswordSettings passwords, MineLoginSource minelogin) {

	private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	public BackendSettings {
		if (!NODE_ID.matcher(nodeId).matches())
			throw new IllegalArgumentException("node-id must be 1-64 letters, digits, '.', '_' or '-'");
	}

	/** As {@link #parse(Map, Path)}, with relative certificate paths relative to the working directory (for tests; the plugin passes its data folder). */
	public static BackendSettings parse(Map<?, ?> root) {
		return parse(root, Path.of(""));
	}

	/** @param root the whole {@code settings.yml} as a map */
	public static BackendSettings parse(Map<?, ?> root, Path dataDirectory) {
		final ConfigSection section = ConfigSection.of("", root);
		final ConfigSection database = section.section("database");

		if (database == null)
			throw section.error("database", "section is missing");

		final ConfigSection migration = section.section("migration");
		final ConfigSection minelogin = migration == null ? null : migration.section("minelogin");

		try {
			return new BackendSettings(section.requireString("node-id"), DatabaseConfig.parse(database, Zone.PRIVATE, dataDirectory), BridgeConfig.parse(section, Zone.PRIVATE), PremiumSettings.parse(orEmpty(section, "premium")),
					PasswordSettings.parse(orEmpty(section, "passwords")), minelogin == null ? new MineLoginSource("", "mineLogin") : new MineLoginSource(minelogin.string("schema", ""), minelogin.string("table", "mineLogin")));

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(ex.getMessage(), ex);
		}
	}

	private static ConfigSection orEmpty(ConfigSection parent, String key) {
		final ConfigSection section = parent.section(key);

		return section != null ? section : ConfigSection.of(parent.path(key), Map.of());
	}
}
