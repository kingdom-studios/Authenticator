package pl.kingdomcraft.authenticator.common.gate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * What to tell an admin when a backend server refuses a player because the proxy has no record of them. The refusal itself is the same
 * whatever the cause, and the player only sees "join through the main address", so the console has to say what is probably wrong.
 */
public final class JoinDiagnostics {

	private JoinDiagnostics() {
	}

	/** The UUID a server in offline mode gives a name when it is not told one. */
	public static UUID offlineUuid(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @param name     the name of the player who was refused
	 * @param uuid     the UUID this server sees for them
	 * @param database where this server looked: schema and endpoint, e.g. {@code authenticator on 10.0.0.5:3306}
	 * @param nodeId   this server's node id
	 */
	public static List<String> unknownToProxy(String name, UUID uuid, String database, String nodeId) {
		final String head = "Refused " + name + " (" + uuid + "): there is no record of this UUID in auth_presence (" + database + ", node '" + nodeId + "'), so the proxy has not logged this player in.";

		if (uuid.equals(offlineUuid(name)))
			return List.of(head,
					"The UUID is the offline-mode UUID of the name " + name + ": this server is not being given the player's profile by Velocity, so it can never match the proxy's record. "
							+ "Set player-info-forwarding-mode = \"modern\" in velocity.toml, and in config/paper-global.yml set proxies.velocity.enabled: true, online-mode: true and "
							+ "secret: the contents of Velocity's forwarding.secret (restart the server after changing it).");

		return List.of(head,
				"Check that: the proxy and this server use the same database (name and server: the proxy's config.yml says which endpoint it uses, '" + database + "' is what this server reads); "
						+ "the proxy's log has no 'Could not write the login state'; the player joined through the proxy and not straight to this server.");
	}
}
