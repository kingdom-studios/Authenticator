package pl.kingdomcraft.authenticator.common.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * The public and private endpoint of one service and which of them to use.
 *
 * @param use      the zone tried first (the proxy uses {@code public}, backend servers {@code private})
 * @param fallback zones tried, in order, when {@code use} cannot be reached
 */
public record ZonedEndpoints<E>(Zone use, List<Zone> fallback, Map<Zone, E> endpoints) {

	/** One endpoint in the order it is tried. */
	public record Candidate<E>(Zone zone, E endpoint) {
	}

	public ZonedEndpoints {
		Objects.requireNonNull(use, "use");
		fallback = List.copyOf(fallback);
		endpoints = endpoints.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(endpoints));

		if (!endpoints.containsKey(use))
			throw new IllegalArgumentException("'use' selects the " + use.key() + " zone but it has no endpoint configured");

		final List<Zone> seen = new ArrayList<>();
		seen.add(use);

		for (final Zone zone : fallback) {
			if (!endpoints.containsKey(zone))
				throw new IllegalArgumentException("'fallback' lists the " + zone.key() + " zone but it has no endpoint configured");

			if (seen.contains(zone))
				throw new IllegalArgumentException("'fallback' lists the " + zone.key() + " zone twice or repeats 'use'");

			seen.add(zone);
		}
	}

	/** Endpoints in the order they should be tried: {@code use} first, then each fallback. */
	public List<Candidate<E>> candidates() {
		final List<Candidate<E>> result = new ArrayList<>();
		result.add(new Candidate<>(this.use, this.endpoints.get(this.use)));

		for (final Zone zone : this.fallback)
			result.add(new Candidate<>(zone, this.endpoints.get(zone)));

		return result;
	}

	/**
	 * Reads {@code use}, {@code fallback} and an {@code endpoints} section with an optional {@code public} and {@code private}
	 * entry from the given section.
	 *
	 * @param defaultUse zone used when the file does not say (proxy: public, backend: private)
	 * @param parser     builds one endpoint from its section
	 */
	public static <E> ZonedEndpoints<E> parse(ConfigSection section, Zone defaultUse, BiFunction<Zone, ConfigSection, E> parser) {
		final Zone use = Zone.parse(section.path("use"), section.string("use", defaultUse.key()));

		final List<Zone> fallback = new ArrayList<>();

		for (final String name : section.stringList("fallback"))
			fallback.add(Zone.parse(section.path("fallback"), name));

		final ConfigSection endpointsSection = section.requireSection("endpoints");
		final Map<Zone, E> endpoints = new EnumMap<>(Zone.class);

		for (final String key : endpointsSection.keys())
			if (java.util.Arrays.stream(Zone.values()).noneMatch(zone -> zone.key().equals(key)))
				throw endpointsSection.error(key, "unknown endpoint, expected 'public' or 'private'");

		for (final Zone zone : Zone.values()) {
			final ConfigSection endpointSection = endpointsSection.section(zone.key());

			if (endpointSection != null)
				endpoints.put(zone, parser.apply(zone, endpointSection));
		}

		try {
			return new ZonedEndpoints<>(use, fallback, endpoints);

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}
	}
}
