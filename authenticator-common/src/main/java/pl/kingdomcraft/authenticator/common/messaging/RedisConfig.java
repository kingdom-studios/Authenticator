package pl.kingdomcraft.authenticator.common.messaging;

import java.util.Map;
import java.util.Objects;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.config.ConfigSection;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;

/**
 * Connection settings for Redis, built from the {@code redis} section of either platform's config. Only used when the bridge runs in
 * {@code redis} mode; the secret and clock skew that apply to every bridge mode live in {@link BridgeConfig}.
 *
 * @param prefix channel and key prefix, see {@link Channels}
 */
public record RedisConfig(ZonedEndpoints<RedisEndpoint> zones, String prefix) {

	public RedisConfig {
		Objects.requireNonNull(zones, "zones");

		if (prefix == null || prefix.isBlank())
			prefix = Channels.DEFAULT_PREFIX;

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
			return new RedisConfig(zones, section.string("prefix", Channels.DEFAULT_PREFIX));

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

	@Override
	public String toString() {
		return "RedisConfig[use=" + this.zones.use().key() + ", prefix=" + this.prefix + "]";
	}
}
