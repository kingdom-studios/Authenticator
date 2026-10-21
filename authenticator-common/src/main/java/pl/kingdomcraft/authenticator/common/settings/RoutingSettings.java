package pl.kingdomcraft.authenticator.common.settings;

import java.util.List;

import pl.kingdomcraft.authenticator.common.config.ConfigSection;

/**
 * @param authServers  servers (names as in velocity.toml) a player stays on until authenticated; the one with the fewest players is used
 * @param lobbyServers where authenticated players go, the same way
 */
public record RoutingSettings(List<String> authServers, List<String> lobbyServers) {

	public RoutingSettings {
		authServers = List.copyOf(authServers);
		lobbyServers = List.copyOf(lobbyServers);

		if (authServers.isEmpty())
			throw new IllegalArgumentException("auth-servers must list at least one server");

		if (lobbyServers.isEmpty())
			throw new IllegalArgumentException("lobby-servers must list at least one server");
	}

	static RoutingSettings parse(ConfigSection section) {
		return section.build(() -> new RoutingSettings(section.stringList("auth-servers"), section.stringList("lobby-servers")));
	}
}
