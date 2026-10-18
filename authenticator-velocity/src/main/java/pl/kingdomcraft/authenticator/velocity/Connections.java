package pl.kingdomcraft.authenticator.velocity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.velocitypowered.api.proxy.Player;

import pl.kingdomcraft.authenticator.common.auth.PlayerSession;

/**
 * Which session was opened for which connection. Keyed by the {@link Player} object, not the UUID, so a connection that is leaving can only
 * ever close its own session, never that of a newer one with the same UUID.
 */
public final class Connections {

	private final Map<Player, PlayerSession> sessions = new ConcurrentHashMap<>();

	public void bind(Player player, PlayerSession session) {
		this.sessions.put(player, session);
	}

	/** The session of this connection, or null if none was opened (the login was refused, or the plugin does not run). */
	public PlayerSession session(Player player) {
		return this.sessions.get(player);
	}

	public PlayerSession unbind(Player player) {
		return this.sessions.remove(player);
	}

	public List<Map.Entry<Player, PlayerSession>> all() {
		return List.copyOf(this.sessions.entrySet());
	}
}
