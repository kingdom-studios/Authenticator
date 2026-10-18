package pl.kingdomcraft.authenticator.common.settings;

import java.time.Duration;

import pl.kingdomcraft.authenticator.common.config.ConfigSection;

/**
 * Limits against bots and guessing, all per IP address.
 *
 * @param connectionsPerIp connections allowed in {@code connectionsWindow}
 * @param accountsPerIp    accounts one address may register in {@code accountsWindow}; 0 for no limit
 * @param attemptsPerIp    wrong passwords from one address in {@code attemptsWindow} before the address is blocked for {@code ipBlock}
 */
public record LimitSettings(int connectionsPerIp, Duration connectionsWindow, int accountsPerIp, Duration accountsWindow, int attemptsPerIp, Duration attemptsWindow, Duration ipBlock) {

	public LimitSettings {
		if (connectionsPerIp < 1 || connectionsPerIp > 1000)
			throw new IllegalArgumentException("connections-per-ip must be between 1 and 1000");

		if (accountsPerIp < 0 || accountsPerIp > 1000)
			throw new IllegalArgumentException("accounts-per-ip must be between 0 and 1000");

		if (attemptsPerIp < 1 || attemptsPerIp > 1000)
			throw new IllegalArgumentException("attempts-per-ip must be between 1 and 1000");

		for (final Duration window : new Duration[] { connectionsWindow, accountsWindow, attemptsWindow, ipBlock })
			if (window.isZero() || window.isNegative())
				throw new IllegalArgumentException("every window and the IP block time must be positive");
	}

	static LimitSettings parse(ConfigSection section) {
		return section.build(() -> new LimitSettings(section.integer("connections-per-ip", 5), Duration.ofSeconds(section.integer("connections-window-seconds", 10)), section.integer("accounts-per-ip", 3),
				Duration.ofHours(section.integer("accounts-window-hours", 168)), section.integer("attempts-per-ip", 8), Duration.ofSeconds(section.integer("attempts-window-seconds", 300)),
				Duration.ofMinutes(section.integer("ip-block-minutes", 30))));
	}
}
