package pl.kingdomcraft.authenticator.common.database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;

/**
 * Connection settings for the shared database, built from the {@code database} section of either platform's config.
 *
 * @param database            schema name, the same for every endpoint
 * @param zones               the public and private endpoint and which to use
 * @param connectionTimeoutMs how long to wait for a connection, also the driver's connect timeout
 */
public record DatabaseConfig(String database, ZonedEndpoints<DatabaseEndpoint> zones, int poolSize, long connectionTimeoutMs) {

	private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z0-9_$-]{1,64}");

	/** Driver read timeout. Without one a dead network link would hang a query forever. */
	static final String DEFAULT_SOCKET_TIMEOUT_MS = "60000";

	public DatabaseConfig {
		Objects.requireNonNull(zones, "zones");

		if (database == null || !SCHEMA_NAME.matcher(database).matches())
			throw new IllegalArgumentException("Invalid database name '" + database + "': use letters, digits, '_', '$' or '-'");

		if (poolSize < 1 || poolSize > 64)
			throw new IllegalArgumentException("pool-size must be between 1 and 64, not " + poolSize);

		if (connectionTimeoutMs < 250)
			throw new IllegalArgumentException("connection-timeout-ms must be at least 250");

		final DatabaseEndpoint publicEndpoint = zones.endpoints().get(Zone.PUBLIC);

		if (publicEndpoint != null && publicEndpoint.sslMode() == SslMode.DISABLE)
			throw new IllegalArgumentException("the public endpoint crosses an untrusted network, so ssl-mode 'disable' is not allowed there (use trust, verify-ca or verify-full)");
	}

	/**
	 * @param section    the {@code database} section
	 * @param defaultUse zone used when the file has no {@code use} key: the proxy passes {@link Zone#PUBLIC}, backend servers {@link Zone#PRIVATE}
	 */
	public static DatabaseConfig parse(ConfigSection section, Zone defaultUse) {
		final ZonedEndpoints<DatabaseEndpoint> zones = ZonedEndpoints.parse(section, defaultUse, (zone, endpoint) -> parseEndpoint(zone, endpoint));

		try {
			return new DatabaseConfig(section.requireString("name"), zones, section.integer("pool-size", 6), section.integer("connection-timeout-ms", 10_000));

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}
	}

	public static DatabaseConfig parse(Map<?, ?> database, Zone defaultUse) {
		return parse(ConfigSection.of("database", database), defaultUse);
	}

	private static DatabaseEndpoint parseEndpoint(Zone zone, ConfigSection section) {
		try {
			return DatabaseEndpoint.parse(zone, section);

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * JDBC URL for one endpoint, without credentials. Option values are written as they are: the MariaDB driver does not decode them,
	 * so encoding would corrupt values such as {@code file:///etc/ssl/ca.pem}. {@link DatabaseEndpoint} refuses values that would
	 * break the URL instead.
	 */
	public String jdbcUrl(DatabaseEndpoint endpoint) {
		final String host = endpoint.host().contains(":") ? "[" + endpoint.host() + "]" : endpoint.host();
		final StringBuilder url = new StringBuilder("jdbc:mariadb://").append(host).append(':').append(endpoint.port()).append('/').append(this.database);

		final Map<String, String> effective = new LinkedHashMap<>();

		// Timestamps are always stored and read as UTC regardless of the JVM or server time zone.
		effective.put("connectionTimeZone", "UTC");
		effective.put("forceConnectionTimeZoneToSession", "true");
		effective.put("sslMode", endpoint.sslMode().driverValue());
		effective.put("connectTimeout", String.valueOf(this.connectionTimeoutMs));
		effective.put("socketTimeout", DEFAULT_SOCKET_TIMEOUT_MS);
		effective.putAll(endpoint.properties());

		char separator = '?';

		for (final Map.Entry<String, String> entry : effective.entrySet()) {
			url.append(separator).append(entry.getKey()).append('=').append(entry.getValue());
			separator = '&';
		}

		return url.toString();
	}
}
