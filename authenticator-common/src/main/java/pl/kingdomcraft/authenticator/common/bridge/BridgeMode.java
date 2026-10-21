package pl.kingdomcraft.authenticator.common.bridge;

import java.util.Locale;

import pl.kingdomcraft.authenticator.common.config.ConfigException;

/** How the proxy and the backend servers exchange messages. In every mode the database is the source of truth. */
public enum BridgeMode {

	/** Signed messages through the player's own connection, on the channel {@code authenticator:bridge}. Needs nothing but the proxy-server link. */
	PLUGIN_MESSAGING("plugin-messaging"),

	/** Signed messages through Redis. Reaches servers that have no player online; needs Redis reachable from the proxy and the servers. */
	REDIS("redis"),

	/** No messages at all: backend servers read the state from the database when a player joins. */
	DATABASE_ONLY("database-only");

	private final String key;

	BridgeMode(String key) {
		this.key = key;
	}

	public String key() {
		return this.key;
	}

	public static BridgeMode parse(String path, String value) {
		final String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');

		for (final BridgeMode mode : values())
			if (mode.key.equals(normalized))
				return mode;

		throw new ConfigException(path + ": unknown bridge mode '" + value + "', expected plugin-messaging, redis or database-only");
	}
}
