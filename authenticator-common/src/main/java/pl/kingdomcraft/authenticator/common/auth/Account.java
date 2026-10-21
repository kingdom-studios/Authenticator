package pl.kingdomcraft.authenticator.common.auth;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import pl.kingdomcraft.authenticator.common.hash.HashAlgo;

/**
 * A row of {@code auth_accounts}.
 *
 * @param uuid         the UUID the player has on every server; it never changes, not even when the account type does
 * @param premiumUuid  the Mojang UUID, set exactly while {@code premium} is true
 * @param passwordSalt appended to the password before hashing; only imported mineLogin passwords have one
 * @param hashAlgo     null when the account has no password or the stored name is not one we know
 * @param version      counts up whenever the password, the account type or the name changes, so that writers and watchers can tell
 */
public record Account(long id, UUID uuid, String username, boolean premium, UUID premiumUuid, String passwordHash, String passwordSalt, HashAlgo hashAlgo, Instant registeredAt,
		InetAddress registeredIp, Instant lastLoginAt, InetAddress lastLoginIp, long version) {

	public boolean hasPassword() {
		return this.passwordHash != null && !this.passwordHash.isEmpty();
	}

	/** The key names are unique by, and the form every lookup uses. */
	public static String key(String username) {
		return username.toLowerCase(Locale.ROOT);
	}
}
