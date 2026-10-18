package pl.kingdomcraft.authenticator.common.auth;

/** Told, after the database has been written, that a player's authentication state changed. The platform sends the message that makes the server notice at once. */
@FunctionalInterface
public interface StateListener {

	/** Called on a database thread: keep it quick and never block in it. */
	void stateChanged(PlayerSession session);
}
