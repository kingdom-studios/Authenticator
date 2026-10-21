package pl.kingdomcraft.authenticator.common.settings;

import java.util.List;
import java.util.Locale;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;

/**
 * @param hasher    how new passwords are hashed
 * @param minLength shortest password accepted
 * @param forbidden passwords refused whatever their length, compared ignoring case
 * @param threads   threads that hash; {@code queueSize} further hashes may wait, the rest are refused at once
 */
public record PasswordSettings(PasswordHasher.Settings hasher, int minLength, List<String> forbidden, int threads, int queueSize) {

	public PasswordSettings {
		forbidden = forbidden.stream().map(entry -> entry.toLowerCase(Locale.ROOT)).toList();

		if (minLength < 1 || minLength > PasswordHasher.MAX_PASSWORD_BYTES)
			throw new IllegalArgumentException("min-length must be between 1 and " + PasswordHasher.MAX_PASSWORD_BYTES);

		if (threads < 1 || threads > 32)
			throw new IllegalArgumentException("threads must be between 1 and 32");

		if (queueSize < 1 || queueSize > 10_000)
			throw new IllegalArgumentException("queue-size must be between 1 and 10000");
	}

	static PasswordSettings parse(ConfigSection section) {
		final String algorithm = section.string("algorithm", "bcrypt").trim().toLowerCase(Locale.ROOT);
		final HashAlgo algo = switch (algorithm) {
			case "bcrypt" -> HashAlgo.BCRYPT;
			case "argon2id" -> HashAlgo.ARGON2ID;
			default -> throw section.error("algorithm", "unknown algorithm '" + algorithm + "', expected bcrypt or argon2id");
		};

		final ConfigSection argon = section.section("argon2");
		final int memory = argon == null ? 19_456 : argon.integer("memory-kib", 19_456);
		final int iterations = argon == null ? 2 : argon.integer("iterations", 2);
		final int parallelism = argon == null ? 1 : argon.integer("parallelism", 1);

		final PasswordHasher.Settings hasher;

		try {
			hasher = new PasswordHasher.Settings(algo, section.integer("bcrypt-cost", 12), memory, iterations, parallelism);

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}

		return section.build(() -> new PasswordSettings(hasher, section.integer("min-length", 6), section.has("forbidden") ? section.stringList("forbidden") : List.of("123456", "12345678", "password", "qwerty", "111111", "abc123", "letmein"),
				section.integer("threads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))), section.integer("queue-size", 64)));
	}
}
