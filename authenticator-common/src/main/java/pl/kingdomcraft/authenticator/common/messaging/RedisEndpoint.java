package pl.kingdomcraft.authenticator.common.messaging;

import java.util.Objects;

import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;

/**
 * One way to reach the Redis server.
 *
 * @param user           ACL user name, empty for the default user
 * @param password       password, empty for none
 * @param ssl            encrypt the connection
 * @param verifyHostname with TLS, also check that the certificate belongs to {@code host}; without it any valid certificate is accepted
 * @param trustStore     with TLS, path of a PKCS12 (or {@code .jks}) file holding the certificates to trust, for a server whose certificate is
 *                       signed by your own CA; empty to trust what the JVM trusts
 */
public record RedisEndpoint(String host, int port, String user, String password, boolean ssl, boolean verifyHostname, String trustStore, String trustStorePassword, int database,
		int connectTimeoutMs) {

	public RedisEndpoint {
		Objects.requireNonNull(user, "user");
		Objects.requireNonNull(password, "password");
		Objects.requireNonNull(trustStore, "trustStore");
		Objects.requireNonNull(trustStorePassword, "trustStorePassword");

		if (host == null || host.isBlank())
			throw new IllegalArgumentException("host is required");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid Redis port: " + port);

		if (database < 0)
			throw new IllegalArgumentException("database cannot be negative");

		if (connectTimeoutMs < 100)
			throw new IllegalArgumentException("connect-timeout-ms must be at least 100");
	}

	/** TLS is the default for the public address and off for the private one. */
	public static RedisEndpoint parse(Zone zone, ConfigSection section) {
		return new RedisEndpoint(section.requireString("host"), section.integer("port", 6379), section.string("user", ""), section.string("password", ""),
				section.bool("ssl", zone == Zone.PUBLIC), section.bool("verify-hostname", true), section.string("ssl-trust-store", ""),
				section.string("ssl-trust-store-password", ""), section.integer("database", 0), section.integer("connect-timeout-ms", 5_000));
	}

	/** {@code host:port} for logs; never includes the password. */
	public String address() {
		return this.host.contains(":") ? "[" + this.host + "]:" + this.port : this.host + ":" + this.port;
	}

	@Override
	public String toString() {
		return "RedisEndpoint[" + (this.user.isEmpty() ? "" : this.user + "@") + this.address() + ", ssl=" + this.ssl + "]";
	}
}
