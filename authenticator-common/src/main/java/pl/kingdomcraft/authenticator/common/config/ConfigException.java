package pl.kingdomcraft.authenticator.common.config;

/** A configuration value is missing or invalid. The message names the full key path, e.g. {@code database.endpoints.public.host}. */
public final class ConfigException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ConfigException(String message) {
		super(message);
	}

	public ConfigException(String message, Throwable cause) {
		super(message, cause);
	}
}
