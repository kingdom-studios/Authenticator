package pl.kingdomcraft.authenticator.common.messaging;

import java.util.UUID;

/** Redis channel and key names, all under a configurable prefix (default {@code authenticator:}) so several networks can share one Redis. */
public record Channels(String prefix) {

	public static final String DEFAULT_PREFIX = "authenticator:";

	public Channels {
		if (prefix == null || prefix.isBlank())
			throw new IllegalArgumentException("Redis prefix cannot be empty");
	}

	public static Channels defaults() {
		return new Channels(DEFAULT_PREFIX);
	}

	/** Authentication state changes, proxy to backend servers. */
	public String state() {
		return prefix + "state";
	}

	/** Account changes made on a backend server, backend to proxy. */
	public String admin() {
		return prefix + "admin";
	}

	public String[] all() {
		return new String[] { state(), admin() };
	}

	/** Key holding a player's current authentication state, written by the proxy before it announces the change. */
	public String stateKey(UUID player) {
		return prefix + "state:" + player;
	}

	/** Key a node refreshes while it is alive; it expires on its own when the node dies. */
	public String nodeKey(String nodeId) {
		return prefix + "node:" + nodeId;
	}
}
