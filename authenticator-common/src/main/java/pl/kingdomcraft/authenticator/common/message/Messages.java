package pl.kingdomcraft.authenticator.common.message;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import pl.kingdomcraft.authenticator.common.auth.AuthService.OpenFailure;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Outcome;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Reason;
import pl.kingdomcraft.authenticator.common.config.ConfigException;

/**
 * The texts players see: a built-in catalog per language, with any of them replaceable from the config file. A text is a MiniMessage
 * template; the placeholders in it ({@code <attempts>}, {@code <until>}...) are filled in by the platform, which escapes the values.
 */
public final class Messages {

	public static final Set<String> LANGUAGES = Set.of("pl", "en");

	private static final String RESOURCE = "/pl/kingdomcraft/authenticator/common/message/messages_%s.properties";

	private final Properties texts;

	private Messages(Properties texts) {
		this.texts = texts;
	}

	/**
	 * @param language  {@code pl} or {@code en}
	 * @param overrides texts from the config file, by key
	 * @throws ConfigException for an unknown language or key: a typo in a key must be noticed at startup, not when the text is needed
	 */
	public static Messages load(String language, Map<String, String> overrides) {
		final String code = language.trim().toLowerCase(Locale.ROOT);

		if (!LANGUAGES.contains(code))
			throw new ConfigException("language: unknown language '" + language + "', expected one of " + new TreeSet<>(LANGUAGES));

		final Properties texts = catalog(code);
		final Set<String> unknown = new TreeSet<>();

		for (final Map.Entry<String, String> override : overrides.entrySet())
			if (texts.containsKey(override.getKey()))
				texts.setProperty(override.getKey(), override.getValue());
			else
				unknown.add(override.getKey());

		if (!unknown.isEmpty())
			throw new ConfigException("messages: unknown keys " + unknown + ". The built-in keys are listed in the plugin's messages_" + code + ".properties.");

		return new Messages(texts);
	}

	/** The built-in catalog of a language; the tests compare them. */
	public static Properties catalog(String language) {
		final Properties properties = new Properties();

		try (InputStream stream = Messages.class.getResourceAsStream(RESOURCE.formatted(language))) {
			if (stream == null)
				throw new IllegalStateException("the built-in messages for '" + language + "' are missing from the plugin");

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				properties.load(reader);
			}

		} catch (final IOException ex) {
			throw new IllegalStateException("could not read the built-in messages for '" + language + "'", ex);
		}

		return properties;
	}

	/** The template for a key. A key that does not exist is a bug in the plugin; the player still gets something readable. */
	public String get(String key) {
		final String text = this.texts.getProperty(key);

		return text != null ? text : "<red>" + key;
	}

	public boolean has(String key) {
		return this.texts.containsKey(key);
	}

	public Set<String> keys() {
		return Set.copyOf(this.texts.stringPropertyNames());
	}

	// ---- keys by what happened ----

	/** The key of a command's outcome, or null for the outcomes that are a success of that particular command and have their own text. */
	public static String key(Outcome outcome) {
		return "outcome." + dashed(outcome.name());
	}

	public static String key(Reason reason) {
		return "deny." + dashed(reason.name());
	}

	public static String key(OpenFailure failure) {
		return "open." + dashed(failure.name());
	}

	/** The key of a password policy violation as {@code Result.detail} carries it ({@code TOO_SHORT:6} or {@code FORBIDDEN}). */
	public static String weakKey(String detail) {
		final int colon = detail.indexOf(':');

		return "weak." + dashed(colon < 0 ? detail : detail.substring(0, colon));
	}

	private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);

	/** An instant as {@code Instant.toString()} wrote it, in a form a player can read; anything else is returned as it is. */
	public static String formatTime(String instant) {
		try {
			return TIME.format(java.time.Instant.parse(instant));

		} catch (final java.time.format.DateTimeParseException ex) {
			return instant;
		}
	}

	/** The values the text of a refusal needs. */
	public static Map<String, String> denyValues(Reason reason, String detail) {
		return switch (reason) {
			case WRONG_CASE -> Map.of("name", detail);
			case IP_BLOCKED -> Map.of("until", formatTime(detail));
			default -> Map.of();
		};
	}

	private static String dashed(String enumName) {
		return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
