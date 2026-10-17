package pl.kingdomcraft.authenticator.common.database;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;

/**
 * One way to reach the MariaDB server.
 *
 * @param properties extra JDBC URL options, e.g. {@code serverSslCert}; applied after the defaults, so they can override them
 */
public record DatabaseEndpoint(String host, int port, String user, String password, SslMode sslMode, Map<String, String> properties) {

	private static final Pattern OPTION_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.]*");

	public DatabaseEndpoint {
		Objects.requireNonNull(sslMode, "sslMode");
		Objects.requireNonNull(password, "password");

		if (host == null || host.isBlank())
			throw new IllegalArgumentException("host is required");

		if (user == null || user.isBlank())
			throw new IllegalArgumentException("user is required");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid database port: " + port);

		properties = Map.copyOf(properties);

		// The options go into the JDBC URL as they are, so anything that could end an option or start a new one is refused.
		for (final Map.Entry<String, String> option : properties.entrySet()) {
			if (!OPTION_NAME.matcher(option.getKey()).matches())
				throw new IllegalArgumentException("invalid option name '" + option.getKey() + "' in properties");

			if (option.getValue().indexOf('&') >= 0 || option.getValue().chars().anyMatch(Character::isISOControl))
				throw new IllegalArgumentException("the value of option '" + option.getKey() + "' contains a character that is not allowed in a connection URL ('&' or a control character)");
		}

		if (host.indexOf('/') >= 0 || host.indexOf('?') >= 0 || host.chars().anyMatch(Character::isWhitespace))
			throw new IllegalArgumentException("invalid host '" + host + "'");
	}

	/** TLS is the default for the public address and off for the private one. */
	public static DatabaseEndpoint parse(Zone zone, ConfigSection section) {
		final SslMode defaultMode = zone == Zone.PUBLIC ? SslMode.VERIFY_FULL : SslMode.DISABLE;

		return new DatabaseEndpoint(section.requireString("host"), section.integer("port", 3306), section.requireString("user"), section.string("password", ""),
				SslMode.parse(section.path("ssl-mode"), section.string("ssl-mode", defaultMode.driverValue())), section.stringMap("properties"));
	}

	/** {@code host:port} for logs; never includes the password. */
	public String address() {
		return this.host.contains(":") ? "[" + this.host + "]:" + this.port : this.host + ":" + this.port;
	}

	@Override
	public String toString() {
		return "DatabaseEndpoint[" + this.user + "@" + this.address() + ", ssl=" + this.sslMode.driverValue() + "]";
	}
}
