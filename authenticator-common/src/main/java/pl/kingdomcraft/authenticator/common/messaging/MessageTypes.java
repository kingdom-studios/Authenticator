package pl.kingdomcraft.authenticator.common.messaging;

/** The {@code type} values of the bridge protocol. */
public final class MessageTypes {

	private MessageTypes() {
	}

	// Proxy to backend servers, on the state channel.

	/** A player's authentication state changed (authenticated, or waiting to log in or register). */
	public static final String AUTH_STATE = "AUTH_STATE";

	/** A player left the network or lost their authentication. */
	public static final String LOGGED_OUT = "LOGGED_OUT";

	/** An account was changed by an admin or by the player (password, account type, removal). */
	public static final String ACCOUNT_UPDATED = "ACCOUNT_UPDATED";

	// Backend server to proxy, on the admin channel.

	/** An admin command on a backend server changed an account, so the proxy must act on the player if they are online. */
	public static final String ACCOUNT_CHANGED = "ACCOUNT_CHANGED";
}
