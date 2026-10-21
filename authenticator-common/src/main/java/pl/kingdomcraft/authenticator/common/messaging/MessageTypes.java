package pl.kingdomcraft.authenticator.common.messaging;

/** The {@code type} values of the bridge protocol. */
public final class MessageTypes {

	private MessageTypes() {
	}

	/** Proxy to the backend server a player is on: the player's authentication state changed. */
	public static final String AUTH_STATE = "AUTH_STATE";

	/** Backend server to proxy: an admin command on a backend server changed an account, so the proxy must act on the player if they are online. */
	public static final String ACCOUNT_CHANGED = "ACCOUNT_CHANGED";
}
