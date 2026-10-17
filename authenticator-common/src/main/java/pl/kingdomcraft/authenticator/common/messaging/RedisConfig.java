package pl.kingdomcraft.authenticator.common.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;

/**
 * Settings for the bridge, built from the {@code redis} section of either platform's config.
 *
 * @param prefix         channel and key prefix, see {@link Channels}
 * @param secret         shared secret every message is signed with; the same on the proxy and on every backend server
 * @param maxSkewSeconds messages whose timestamp differs from this node's clock by more than this are dropped, so clocks must be in sync
 */
public record RedisConfig(ZonedEndpoints<RedisEndpoint> zones, String prefix, String secret, int maxSkewSeconds) {

	public RedisConfig {
		Objects.requireNonNull(zones, "zones");
		Objects.requireNonNull(secret, "secret");

		if (prefix == null || prefix.isBlank())
			prefix = Channels.DEFAULT_PREFIX;

		if (secret.length() < HmacSigner.MIN_SECRET_LENGTH)
			throw new IllegalArgumentException("secret must be at least " + HmacSigner.MIN_SECRET_LENGTH + " characters long");

		if (maxSkewSeconds < 1 || maxSkewSeconds > 300)
			throw new IllegalArgumentException("max-skew-seconds must be between 1 and 300, not " + maxSkewSeconds);

		final RedisEndpoint publicEndpoint = zones.endpoints().get(Zone.PUBLIC);

		if (publicEndpoint != null && !publicEndpoint.ssl())
			throw new IllegalArgumentException("the public endpoint crosses an untrusted network, so it must use ssl: true");
	}

	/**
	 * @param section    the {@code redis} section
	 * @param defaultUse zone used when the file has no {@code use} key: the proxy passes {@link Zone#PUBLIC}, backend servers {@link Zone#PRIVATE}
	 */
	public static RedisConfig parse(ConfigSection section, Zone defaultUse) {
		final ZonedEndpoints<RedisEndpoint> zones = ZonedEndpoints.parse(section, defaultUse, (zone, endpoint) -> parseEndpoint(zone, endpoint));

		try {
			return new RedisConfig(zones, section.string("prefix", Channels.DEFAULT_PREFIX), section.requireString("secret"), section.integer("max-skew-seconds", 30));

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}
	}

	public static RedisConfig parse(Map<?, ?> redis, Zone defaultUse) {
		return parse(ConfigSection.of("redis", redis), defaultUse);
	}

	private static RedisEndpoint parseEndpoint(Zone zone, ConfigSection section) {
		try {
			return RedisEndpoint.parse(zone, section);

		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(section.path() + ": " + ex.getMessage(), ex);
		}
	}

	public HmacSigner signer() {
		return new HmacSigner(this.secret);
	}

	public Duration maxSkew() {
		return Duration.ofSeconds(this.maxSkewSeconds);
	}

	@Override
	public String toString() {
		return "RedisConfig[use=" + this.zones.use().key() + ", prefix=" + this.prefix + ", secret=***]";
	}
}
