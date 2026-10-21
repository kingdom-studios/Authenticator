package pl.kingdomcraft.authenticator.common.auth;

import java.util.Locale;

import pl.kingdomcraft.authenticator.common.config.ConfigException;

/** What happens to a new name that is also an original Minecraft name. */
public enum PremiumPolicy {

	/**
	 * Original names are protected: whoever joins with one has to prove, through Mojang, that the account is theirs. A cracked client can
	 * never take such a name. The safe default.
	 */
	PROTECT,

	/**
	 * Original names are not protected. A player whose client is not an original one is let in as a non-premium player (after one failed
	 * attempt, or at once when the launcher gives itself away), and the real owner keeps playing as before. Anyone can register the name
	 * first, so the real owner of a name that is already taken has to use {@code /premium} to claim it back.
	 */
	ALLOW_CLAIM_AUTO;

	public static PremiumPolicy parse(String path, String value) {
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		} catch (final IllegalArgumentException ex) {
			throw new ConfigException(path + ": unknown premium policy '" + value + "', expected PROTECT or ALLOW_CLAIM_AUTO");
		}
	}
}
