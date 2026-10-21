package pl.kingdomcraft.authenticator.common.hash;

import java.util.Locale;
import java.util.regex.Pattern;

/** How a stored password hash was made; the value of {@code auth_accounts.hash_algo}. */
public enum HashAlgo {

	/** BCrypt. The default for new passwords, and the one mineLogin also understands, so a rollback does not lock out new accounts. */
	BCRYPT(false),

	/** Argon2id with the parameters in the PHC string itself. Optional for new passwords. */
	ARGON2ID(false),

	/** Argon2i or Argon2d, as written by mineLogin. Accepted for old accounts and replaced at the next login. */
	ARGON2(true),

	/** Hex SHA-256 of the password followed by the account's salt. Accepted for old accounts and replaced at the next login. */
	SHA256(true),

	/** Hex SHA-512 of the password followed by the account's salt. Accepted for old accounts and replaced at the next login. */
	SHA512(true);

	private static final Pattern HEX_64 = Pattern.compile("[0-9a-fA-F]{64}");
	private static final Pattern HEX_128 = Pattern.compile("[0-9a-fA-F]{128}");

	private final boolean legacy;

	HashAlgo(boolean legacy) {
		this.legacy = legacy;
	}

	/** True for algorithms that are only read, never written. */
	public boolean legacy() {
		return this.legacy;
	}

	/** @return the algorithm named in a database column or in mineLogin's {@code hashType}, or null if it is not one we can verify */
	public static HashAlgo parse(String name) {
		if (name == null)
			return null;

		try {
			return valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (final IllegalArgumentException ex) {
			return null;
		}
	}

	/** Guesses the algorithm from the shape of a hash, the way mineLogin does when it has no {@code hashType}; null if it is none we verify. */
	public static HashAlgo detect(String hash) {
		if (hash == null || hash.isBlank())
			return null;

		if (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2x$") || hash.startsWith("$2y$"))
			return BCRYPT;

		if (hash.startsWith("$argon2id$"))
			return ARGON2ID;

		if (hash.startsWith("$argon2"))
			return ARGON2;

		if (HEX_64.matcher(hash).matches())
			return SHA256;

		return HEX_128.matcher(hash).matches() ? SHA512 : null;
	}
}
