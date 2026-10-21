package pl.kingdomcraft.authenticator.common.config;

import java.util.Locale;

/**
 * The network a connection goes through. The proxy sits outside the infrastructure and reaches MariaDB and Redis over
 * their public address; the backend servers sit inside and use the private one.
 */
public enum Zone {

	PUBLIC,
	PRIVATE;

	/** The lower-case name used in configuration files. */
	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Zone parse(String path, String value) {
		for (final Zone zone : values())
			if (zone.key().equalsIgnoreCase(value.trim()))
				return zone;

		throw new ConfigException(path + ": unknown network zone '" + value + "', expected 'public' or 'private'");
	}
}
