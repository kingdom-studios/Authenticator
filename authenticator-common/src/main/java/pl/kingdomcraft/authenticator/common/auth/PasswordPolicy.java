package pl.kingdomcraft.authenticator.common.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;

/** What a new password must satisfy. */
public final class PasswordPolicy {

	public enum Violation {

		TOO_SHORT,

		/** BCrypt only reads 72 bytes, so a longer password would silently be cut. */
		TOO_LONG,

		/** Commands are split at spaces, so such a password could not be typed back. */
		WHITESPACE,

		SAME_AS_NAME,

		FORBIDDEN
	}

	private final int minLength;
	private final List<String> forbidden;

	/** @param forbidden lower-case passwords that are always refused */
	public PasswordPolicy(int minLength, List<String> forbidden) {
		this.minLength = minLength;
		this.forbidden = List.copyOf(forbidden);
	}

	public int minLength() {
		return this.minLength;
	}

	public Optional<Violation> check(String username, String password) {
		if (password.codePointCount(0, password.length()) < this.minLength)
			return Optional.of(Violation.TOO_SHORT);

		if (password.getBytes(StandardCharsets.UTF_8).length > PasswordHasher.MAX_PASSWORD_BYTES)
			return Optional.of(Violation.TOO_LONG);

		if (password.codePoints().anyMatch(Character::isWhitespace))
			return Optional.of(Violation.WHITESPACE);

		if (password.equalsIgnoreCase(username))
			return Optional.of(Violation.SAME_AS_NAME);

		if (this.forbidden.contains(password.toLowerCase(Locale.ROOT)))
			return Optional.of(Violation.FORBIDDEN);

		return Optional.empty();
	}
}
