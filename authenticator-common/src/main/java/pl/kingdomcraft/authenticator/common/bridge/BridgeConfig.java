package pl.kingdomcraft.authenticator.common.bridge;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.HmacSigner;
import pl.kingdomcraft.authenticator.common.messaging.RedisConfig;

/**
 * Settings of the bridge between the proxy and the backend servers, from the {@code bridge} section (and, in {@code redis} mode, the
 * {@code redis} section) of either platform's config.
 *
 * @param secret              shared secret every message is signed with, the same on the proxy and on every server; not needed without messages
 * @param maxSkewSeconds      messages whose time differs from this node's clock by more than this are dropped, so clocks must be in sync
 * @param databasePollSeconds how often the proxy looks in the database for changes made behind its back (for example by an admin on a
 *                            backend server); 0 turns it off
 * @param redis               present exactly when the mode is {@link BridgeMode#REDIS}
 */
public record BridgeConfig(BridgeMode mode, String secret, int maxSkewSeconds, int databasePollSeconds, RedisConfig redis) {

	public BridgeConfig {
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(secret, "secret");

		if (mode != BridgeMode.DATABASE_ONLY && secret.length() < HmacSigner.MIN_SECRET_LENGTH)
			throw new IllegalArgumentException("secret must be at least " + HmacSigner.MIN_SECRET_LENGTH + " characters long (every bridge message is signed with it)");

		if (maxSkewSeconds < 1 || maxSkewSeconds > 300)
			throw new IllegalArgumentException("max-skew-seconds must be between 1 and 300, not " + maxSkewSeconds);

		if (databasePollSeconds < 0 || databasePollSeconds > 3600)
			throw new IllegalArgumentException("database-poll-seconds must be between 0 and 3600, not " + databasePollSeconds);

		if ((mode == BridgeMode.REDIS) != (redis != null))
			throw new IllegalArgumentException(mode == BridgeMode.REDIS ? "mode redis needs Redis settings" : "Redis settings are only for mode redis");
	}

	/**
	 * @param root       the whole config file, which holds the {@code bridge} section and, for mode {@code redis}, a {@code redis} section beside it
	 * @param defaultUse the zone used when a Redis {@code use} key is missing: the proxy passes {@link Zone#PUBLIC}, backend servers {@link Zone#PRIVATE}
	 */
	public static BridgeConfig parse(ConfigSection root, Zone defaultUse) {
		final ConfigSection section = root.section("bridge");

		if (section == null)
			throw root.error("bridge", "section is missing");

		final BridgeMode mode = BridgeMode.parse(section.path("mode"), section.string("mode", BridgeMode.PLUGIN_MESSAGING.key()));
		final RedisConfig redis = mode == BridgeMode.REDIS ? RedisConfig.parse(requireRedisSection(root), defaultUse) : null;

		try {
			return new BridgeConfig(mode, mode == BridgeMode.DATABASE_ONLY ? section.string("secret", "") : section.requireString("secret"), section.integer("max-skew-seconds", 30),
					section.integer("database-poll-seconds", 5), redis);

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}
	}

	public static BridgeConfig parse(Map<?, ?> root, Zone defaultUse) {
		return parse(ConfigSection.of("", root), defaultUse);
	}

	private static ConfigSection requireRedisSection(ConfigSection root) {
		final ConfigSection redis = root.section("redis");

		if (redis == null)
			throw root.error("redis", "section is missing, but bridge.mode is redis");

		return redis;
	}

	public HmacSigner signer() {
		return new HmacSigner(this.secret);
	}

	public EnvelopeVerifier verifier(Clock clock, Logger logger) {
		return new EnvelopeVerifier(this.signer(), java.time.Duration.ofSeconds(this.maxSkewSeconds), clock, logger);
	}

	@Override
	public String toString() {
		return "BridgeConfig[mode=" + this.mode.key() + ", secret=***, maxSkewSeconds=" + this.maxSkewSeconds + ", databasePollSeconds=" + this.databasePollSeconds + ", redis=" + this.redis + "]";
	}
}
