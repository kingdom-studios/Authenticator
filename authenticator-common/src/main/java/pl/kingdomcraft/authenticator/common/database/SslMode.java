package pl.kingdomcraft.authenticator.common.database;

import java.util.Locale;

import pl.kingdomcraft.authenticator.common.config.ConfigException;

/** How the MariaDB connection is encrypted; the values are the ones the MariaDB driver's {@code sslMode} option takes. */
public enum SslMode {

	/** Plain text. Acceptable only inside the private network. */
	DISABLE("disable"),

	/** Encrypted, but the server certificate is not checked. Protects against sniffing, not against a man in the middle. */
	TRUST("trust"),

	/** Encrypted and the certificate chain is checked. */
	VERIFY_CA("verify-ca"),

	/** Encrypted, the chain and the host name are checked. */
	VERIFY_FULL("verify-full");

	private final String driverValue;

	SslMode(String driverValue) {
		this.driverValue = driverValue;
	}

	public String driverValue() {
		return this.driverValue;
	}

	public static SslMode parse(String path, String value) {
		final String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');

		for (final SslMode mode : values())
			if (mode.driverValue.equals(normalized))
				return mode;

		throw new ConfigException(path + ": unknown ssl-mode '" + value + "', expected disable, trust, verify-ca or verify-full");
	}
}
