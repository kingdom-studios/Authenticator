package pl.kingdomcraft.authenticator.common.auth;

/** How a player became authenticated. */
public enum AuthMethod {

	/** Original account, confirmed by Mojang. */
	PREMIUM,

	/** Typed the password (or just chose it when registering). */
	PASSWORD,

	/** Came back from the address of an earlier login. */
	SESSION,

	/** An admin logged the player in. */
	FORCED
}
