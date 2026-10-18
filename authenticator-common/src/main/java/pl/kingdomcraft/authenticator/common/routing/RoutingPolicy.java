package pl.kingdomcraft.authenticator.common.routing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import pl.kingdomcraft.authenticator.common.settings.RoutingSettings;

/**
 * Where players may go: until they are authenticated, only to a waiting server; afterwards anywhere. Pure rules over server names, so the
 * proxy only has to ask them. A load function reports how many players a server has, or a negative number for a server that does not exist.
 */
public final class RoutingPolicy {

	/** What to do with an attempt to connect to a server. */
	public enum Verdict {

		/** Let it happen. */
		ALLOW,

		/** The player may not go there, and they are not on any server yet: send them to a waiting server instead. */
		REDIRECT_TO_AUTH,

		/** The player may not go there and is already on a server: stay. */
		DENY
	}

	private final List<String> authServers;
	private final List<String> lobbyServers;
	private final Set<String> authKeys;

	public RoutingPolicy(RoutingSettings settings) {
		this.authServers = settings.authServers();
		this.lobbyServers = settings.lobbyServers();
		this.authKeys = settings.authServers().stream().map(RoutingPolicy::key).collect(Collectors.toUnmodifiableSet());
	}

	private static String key(String serverName) {
		return serverName.toLowerCase(Locale.ROOT);
	}

	public boolean isAuthServer(String serverName) {
		return this.authKeys.contains(key(serverName));
	}

	/** The waiting server with the fewest players. */
	public Optional<String> chooseAuth(ToIntFunction<String> load) {
		return leastLoaded(this.authServers, load);
	}

	/** The lobby with the fewest players. */
	public Optional<String> chooseLobby(ToIntFunction<String> load) {
		return leastLoaded(this.lobbyServers, load);
	}

	/** The server a player starts on. */
	public Optional<String> initial(boolean authenticated, ToIntFunction<String> load) {
		return authenticated ? this.chooseLobby(load) : this.chooseAuth(load);
	}

	/**
	 * @param authenticated whether the player has logged in
	 * @param onAServer     whether the player is on a server now (false for the first connection)
	 * @param target        the server they try to reach
	 */
	public Verdict connect(boolean authenticated, boolean onAServer, String target) {
		if (authenticated || this.isAuthServer(target))
			return Verdict.ALLOW;

		return onAServer ? Verdict.DENY : Verdict.REDIRECT_TO_AUTH;
	}

	/**
	 * Where a player should be moved after their state changed, or empty to leave them be.
	 *
	 * @param current the server they are on, or null while connecting
	 */
	public Optional<String> moveAfterChange(boolean authenticated, String current, ToIntFunction<String> load) {
		if (current == null)
			return Optional.empty();

		if (authenticated)
			return this.isAuthServer(current) ? this.chooseLobby(load) : Optional.empty();

		return this.isAuthServer(current) ? Optional.empty() : this.chooseAuth(load);
	}

	private static Optional<String> leastLoaded(List<String> names, ToIntFunction<String> load) {
		String best = null;
		int bestLoad = Integer.MAX_VALUE;

		for (final String name : names) {
			final int players = load.applyAsInt(name);

			if (players >= 0 && players < bestLoad) {
				best = name;
				bestLoad = players;
			}
		}

		return Optional.ofNullable(best);
	}
}
