package pl.kingdomcraft.authenticator.common.settings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import pl.kingdomcraft.authenticator.common.auth.PremiumPolicy;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Timing;

/**
 * @param apis    lookup services in the order they are asked
 * @param timeout how long one lookup may take before the next service is tried
 * @param claim   with {@link PremiumPolicy#ALLOW_CLAIM_AUTO}: how long a failed online attempt makes the next one offline
 */
public record PremiumSettings(PremiumPolicy policy, List<String> apis, Duration timeout, Timing timing, Duration claim) {

	public PremiumSettings {
		Objects.requireNonNull(policy, "policy");
		apis = List.copyOf(apis);

		if (apis.isEmpty())
			throw new IllegalArgumentException("apis must list at least one lookup service");

		for (final String api : apis)
			if (!List.of("mojang", "ashcon", "minetools").contains(api.trim().toLowerCase(Locale.ROOT)))
				throw new IllegalArgumentException("unknown lookup service '" + api + "', expected mojang, ashcon or minetools");

		if (timeout.toMillis() < 200 || timeout.toSeconds() > 30)
			throw new IllegalArgumentException("timeout-ms must be between 200 and 30000");

		if (claim.toSeconds() < 5 || claim.toMinutes() > 60)
			throw new IllegalArgumentException("claim-seconds must be between 5 and 3600");
	}

	static PremiumSettings parse(ConfigSection section) {
		return section.build(() -> new PremiumSettings(PremiumPolicy.parse(section.path("policy"), section.string("policy", "PROTECT")), section.stringList("apis").isEmpty() ? List.of("mojang", "ashcon", "minetools") : section.stringList("apis"),
				Duration.ofMillis(section.integer("timeout-ms", 3_000)),
				new Timing(Duration.ofSeconds(positive(section, "memory-seconds", 600)), Duration.ofSeconds(positive(section, "unknown-seconds", 15)), Duration.ofHours(positive(section, "positive-hours", 24)),
						Duration.ofHours(positive(section, "negative-hours", 1))),
				Duration.ofSeconds(section.integer("claim-seconds", 60))));
	}

	private static int positive(ConfigSection section, String key, int fallback) {
		final int value = section.integer(key, fallback);

		if (value < 1)
			throw section.error(key, "must be at least 1");

		return value;
	}
}
