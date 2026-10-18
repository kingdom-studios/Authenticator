package pl.kingdomcraft.authenticator.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view over one level of a parsed YAML file, so that Velocity (Configurate) and Paper (Foundation) can hand
 * their settings to the platform-free code as plain maps.
 *
 * <p>Keys are matched ignoring case and treating {@code _} and {@code -} alike, so {@code pool-size} (Velocity style) and
 * {@code Pool_Size} (Foundation style) are the same key. Every error names the full path of the offending key.
 */
public final class ConfigSection {

	private final String path;
	private final Map<String, Object> values = new LinkedHashMap<>();

	private ConfigSection(String path, Map<?, ?> raw) {
		this.path = path;

		for (final Map.Entry<?, ?> entry : raw.entrySet()) {
			final String key = normalize(String.valueOf(entry.getKey()));

			if (this.values.put(key, entry.getValue()) != null)
				throw new ConfigException(this.path(key) + ": key is defined twice (keys are case-insensitive, '_' equals '-')");
		}
	}

	public static ConfigSection of(String path, Map<?, ?> raw) {
		return new ConfigSection(path, raw);
	}

	static String normalize(String key) {
		return key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	public String path() {
		return this.path;
	}

	public String path(String key) {
		return this.path.isEmpty() ? key : this.path + "." + key;
	}

	public Set<String> keys() {
		return this.values.keySet();
	}

	public boolean has(String key) {
		return this.values.get(normalize(key)) != null;
	}

	public ConfigException error(String key, String message) {
		return new ConfigException(this.path(key) + ": " + message);
	}

	/** Builds a value from this section's settings; a validation failure inside it is reported as a {@link ConfigException} naming this section. */
	public <T> T build(java.util.function.Supplier<T> constructor) {
		try {
			return constructor.get();

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(this.path.isEmpty() ? ex.getMessage() : this.path + ": " + ex.getMessage(), ex);
		}
	}

	/** @return the nested section, or null if the key is absent */
	public ConfigSection section(String key) {
		final Object value = this.values.get(normalize(key));

		if (value == null)
			return null;

		if (!(value instanceof Map<?, ?> map))
			throw this.error(key, "expected a section, found '" + value + "'");

		return new ConfigSection(this.path(key), map);
	}

	public ConfigSection requireSection(String key) {
		final ConfigSection section = this.section(key);

		if (section == null)
			throw this.error(key, "section is missing");

		return section;
	}

	public String requireString(String key) {
		final String value = this.string(key, null);

		if (value == null || value.isBlank())
			throw this.error(key, "value is required");

		return value;
	}

	public String string(String key, String fallback) {
		final Object value = this.values.get(normalize(key));

		if (value == null)
			return fallback;

		if (value instanceof Map || value instanceof Iterable)
			throw this.error(key, "expected a single value, found a " + (value instanceof Map ? "section" : "list"));

		return String.valueOf(value);
	}

	public int integer(String key, int fallback) {
		final Object value = this.values.get(normalize(key));

		if (value == null)
			return fallback;

		try {
			if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
				return Math.toIntExact(((Number) value).longValue());

			if (value instanceof String text)
				return Integer.parseInt(text.trim());

		} catch (final ArithmeticException | NumberFormatException ex) {
			// falls through to the error below
		}

		throw this.error(key, "expected a whole number, found '" + value + "'");
	}

	public boolean bool(String key, boolean fallback) {
		final Object value = this.values.get(normalize(key));

		if (value == null)
			return fallback;

		if (value instanceof Boolean flag)
			return flag;

		if (value instanceof String text) {
			if (text.equalsIgnoreCase("true"))
				return true;

			if (text.equalsIgnoreCase("false"))
				return false;
		}

		throw this.error(key, "expected true or false, found '" + value + "'");
	}

	/** A list of strings; an absent key is an empty list and a single value is a list of one. */
	public List<String> stringList(String key) {
		final Object value = this.values.get(normalize(key));

		if (value == null)
			return List.of();

		if (value instanceof Map)
			throw this.error(key, "expected a list, found a section");

		final List<String> result = new ArrayList<>();

		if (value instanceof Iterable<?> iterable) {
			for (final Object element : iterable) {
				if (element == null || element instanceof Map || element instanceof Iterable)
					throw this.error(key, "list entries must be plain values");

				result.add(String.valueOf(element));
			}
		} else
			result.add(String.valueOf(value));

		return result;
	}

	/** A map of free-form text values (keys are kept as written, not normalized). Absent means empty. */
	public Map<String, String> stringMap(String key) {
		final Object value = this.values.get(normalize(key));

		if (value == null)
			return Map.of();

		if (!(value instanceof Map<?, ?> map))
			throw this.error(key, "expected a section of key: value pairs");

		final Map<String, String> result = new LinkedHashMap<>();

		for (final Map.Entry<?, ?> entry : map.entrySet()) {
			if (entry.getValue() == null || entry.getValue() instanceof Map || entry.getValue() instanceof Iterable)
				throw this.error(key + "." + entry.getKey(), "expected a plain value");

			result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}

		return result;
	}
}
