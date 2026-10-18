package pl.kingdomcraft.authenticator.common.settings;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import pl.kingdomcraft.authenticator.common.config.ConfigSection;

/**
 * @param timeout         how long a player has to log in or register before being kicked
 * @param maxAttempts     wrong passwords in one visit before the player is kicked
 * @param sessionDuration how long a non-premium player may return from the same address without the password; zero turns sessions off
 * @param nickname        names that may connect at all
 */
public record LoginSettings(Duration timeout, int maxAttempts, Duration sessionDuration, Pattern nickname) {

	public LoginSettings {
		Objects.requireNonNull(nickname, "nickname");

		if (timeout.toSeconds() < 5 || timeout.toMinutes() > 60)
			throw new IllegalArgumentException("timeout-seconds must be between 5 and 3600");

		if (maxAttempts < 1 || maxAttempts > 20)
			throw new IllegalArgumentException("max-attempts must be between 1 and 20");

		if (sessionDuration.isNegative())
			throw new IllegalArgumentException("session-minutes cannot be negative");
	}

	static LoginSettings parse(ConfigSection section) {
		final Pattern nickname;

		try {
			nickname = Pattern.compile(section.string("nickname-regex", "^[a-zA-Z0-9_]{3,16}$"));

		} catch (final PatternSyntaxException ex) {
			throw section.error("nickname-regex", "not a valid regular expression: " + ex.getDescription());
		}

		return section.build(() -> new LoginSettings(Duration.ofSeconds(section.integer("timeout-seconds", 60)), section.integer("max-attempts", 3), Duration.ofMinutes(section.integer("session-minutes", 4320)), nickname));
	}

	public boolean sessionsEnabled() {
		return !this.sessionDuration.isZero();
	}
}
