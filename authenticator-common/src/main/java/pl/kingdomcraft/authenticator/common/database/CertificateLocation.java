package pl.kingdomcraft.authenticator.common.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import pl.kingdomcraft.authenticator.common.config.ConfigException;

/**
 * The MariaDB driver takes the server certificate ({@code serverSslCert}) as a URL, a PEM text or a classpath resource, and
 * rejects a plain file path with "Self signed certificates...". Admins write plain paths, so they are turned into
 * {@code file:///...} here, and a file that is not there is reported at startup instead of as a TLS error at the first connection.
 */
public final class CertificateLocation {

	/** The driver's name for the option; any capitalisation in a config is replaced by this. */
	public static final String OPTION = "serverSslCert";

	private CertificateLocation() {
	}

	/** True for the option's name in any capitalisation. */
	public static boolean isOption(String name) {
		return OPTION.equalsIgnoreCase(name);
	}

	/**
	 * @param where         the config key, for the error message
	 * @param value         what the admin wrote
	 * @param dataDirectory what a relative path is relative to: the plugin's data folder
	 * @return the value the driver understands
	 * @throws ConfigException when a path points to a file that does not exist or cannot be read
	 */
	public static String normalize(String where, String value, Path dataDirectory) {
		final String trimmed = value.trim();

		if (trimmed.isEmpty() || trimmed.startsWith("file:") || trimmed.startsWith("classpath:") || trimmed.startsWith("-----BEGIN CERTIFICATE-----"))
			return trimmed;

		final Path file;

		try {
			final Path given = Path.of(trimmed);

			file = given.isAbsolute() ? given.normalize() : dataDirectory.toAbsolutePath().resolve(given).normalize();

		} catch (final InvalidPathException ex) {
			throw new ConfigException(where + ": '" + trimmed + "' is not a valid file path: " + ex.getReason(), ex);
		}

		if (!Files.exists(file))
			throw new ConfigException(where + ": the certificate file " + file + " does not exist" + (Path.of(trimmed).isAbsolute() ? "" : " (a relative path is looked for in " + dataDirectory.toAbsolutePath() + ")"));

		if (!Files.isRegularFile(file))
			throw new ConfigException(where + ": " + file + " is not a file");

		try (InputStream stream = Files.newInputStream(file)) {
			if (stream.read() < 0)
				throw new ConfigException(where + ": the certificate file " + file + " is empty");

		} catch (final IOException ex) {
			throw new ConfigException(where + ": the certificate file " + file + " cannot be read: " + ex.getMessage(), ex);
		}

		return file.toUri().toString();
	}
}
