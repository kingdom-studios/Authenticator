package pl.kingdomcraft.authenticator.common.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import com.password4j.Argon2Function;
import com.password4j.BcryptFunction;
import com.password4j.types.Argon2;
import com.password4j.types.Bcrypt;

/**
 * Hashes new passwords and verifies old ones. New passwords use the configured algorithm; passwords made by mineLogin (BCrypt, Argon2,
 * SHA-256, SHA-512) are verified exactly the way mineLogin made them: the account's salt, if it has one, is appended to the password.
 * After a successful login {@link #needsRehash} says whether the stored hash should be replaced.
 *
 * <p>Hashing is slow on purpose. Never call it on a thread that must not block: use a {@link HashPool}. Thread-safe.
 */
public final class PasswordHasher {

	/** BCrypt reads at most 72 bytes of the password; the rest would be ignored without a word. */
	public static final int MAX_PASSWORD_BYTES = 72;

	/**
	 * @param algorithm the algorithm for new passwords, {@link HashAlgo#BCRYPT} or {@link HashAlgo#ARGON2ID}
	 */
	public record Settings(HashAlgo algorithm, int bcryptCost, int argonMemoryKib, int argonIterations, int argonParallelism) {

		/** The OWASP-recommended Argon2id minimum (19 MiB, 2 iterations) and BCrypt cost 12, the one mineLogin used. */
		public static Settings defaults() {
			return new Settings(HashAlgo.BCRYPT, 12, 19_456, 2, 1);
		}

		public Settings {
			if (algorithm != HashAlgo.BCRYPT && algorithm != HashAlgo.ARGON2ID)
				throw new IllegalArgumentException("new passwords can only use bcrypt or argon2id, not " + algorithm);

			if (bcryptCost < 4 || bcryptCost > 16)
				throw new IllegalArgumentException("bcrypt-cost must be between 4 and 16, not " + bcryptCost);

			if (argonMemoryKib < 8 || argonMemoryKib > 1_048_576)
				throw new IllegalArgumentException("argon2 memory-kib must be between 8 and 1048576, not " + argonMemoryKib);

			if (argonIterations < 1 || argonIterations > 20)
				throw new IllegalArgumentException("argon2 iterations must be between 1 and 20, not " + argonIterations);

			if (argonParallelism < 1 || argonParallelism > 16)
				throw new IllegalArgumentException("argon2 parallelism must be between 1 and 16, not " + argonParallelism);
		}
	}

	/** A new hash and the algorithm that made it. New hashes never need a separate salt. */
	public record Hashed(String hash, HashAlgo algorithm) {
	}

	private final Settings settings;

	public PasswordHasher(Settings settings) {
		this.settings = settings;
	}

	public Settings settings() {
		return this.settings;
	}

	public Hashed hash(String password) {
		return switch (this.settings.algorithm()) {
			// $2a$ is what mineLogin writes and reads, so a rollback to it keeps working.
			case BCRYPT -> new Hashed(BcryptFunction.getInstance(Bcrypt.A, this.settings.bcryptCost()).hash(password).getResult(), HashAlgo.BCRYPT);
			case ARGON2ID -> new Hashed(Argon2Function.getInstance(this.settings.argonMemoryKib(), this.settings.argonIterations(), this.settings.argonParallelism(), 32, Argon2.ID).hash(password).getResult(),
					HashAlgo.ARGON2ID);
			default -> throw new IllegalStateException("not a hashing algorithm for new passwords: " + this.settings.algorithm());
		};
	}

	/**
	 * @param salt the account's {@code password_salt}, appended to the password as mineLogin does; null or empty for none
	 * @return false for a wrong password and for a stored hash that is malformed or of a kind we cannot read
	 */
	public boolean verify(HashAlgo algorithm, String hash, String salt, String password) {
		if (algorithm == null || hash == null || hash.isEmpty())
			return false;

		final String input = salt == null || salt.isEmpty() ? password : password + salt;

		try {
			return switch (algorithm) {
				case BCRYPT -> BcryptFunction.getInstanceFromHash(hash).check(input, hash);
				case ARGON2, ARGON2ID -> Argon2Function.getInstanceFromHash(hash).check(input, hash);
				case SHA256 -> digestMatches("SHA-256", hash, input);
				case SHA512 -> digestMatches("SHA-512", hash, input);
			};

		} catch (final RuntimeException ex) {
			return false; // a stored value we cannot parse never matches
		}
	}

	/** Whether a hash that just verified should be replaced by a fresh one made with the current settings. */
	public boolean needsRehash(HashAlgo algorithm, String hash) {
		if (algorithm.legacy() || algorithm != this.settings.algorithm())
			return true;

		try {
			if (algorithm == HashAlgo.BCRYPT)
				return BcryptFunction.getInstanceFromHash(hash).getLogarithmicRounds() < this.settings.bcryptCost();

			final Argon2Function stored = Argon2Function.getInstanceFromHash(hash);

			return stored.getMemory() < this.settings.argonMemoryKib() || stored.getIterations() < this.settings.argonIterations() || stored.getParallelism() < this.settings.argonParallelism();

		} catch (final RuntimeException ex) {
			return true;
		}
	}

	private static boolean digestMatches(String algorithm, String expectedHex, String input) {
		try {
			final byte[] actual = MessageDigest.getInstance(algorithm).digest(input.getBytes(StandardCharsets.UTF_8));
			final byte[] expected = HexFormat.of().parseHex(expectedHex.toLowerCase(Locale.ROOT));

			return MessageDigest.isEqual(actual, expected);

		} catch (final NoSuchAlgorithmException | IllegalArgumentException ex) {
			return false;
		}
	}
}
