package pl.kingdomcraft.authenticator.common.auth;

/** How far a connected player got. */
public enum AuthState {

	/** Has an account and must type the password. */
	PENDING_LOGIN,

	/** New non-premium player who must choose a password. */
	PENDING_REGISTER,

	/** May play. */
	AUTHENTICATED
}
