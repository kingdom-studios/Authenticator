package pl.kingdomcraft.authenticator.common.gate;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;

/**
 * What this server knows about who has logged in, in memory and nothing else. A player the gate has never heard of is <em>not</em> logged
 * in: everything that guards the server asks {@link #authenticated} and gets {@code false} until the proxy's word has arrived, so a server
 * that is still starting, or whose database is down, is closed rather than open.
 *
 * <p>A state carries the proxy's {@code epoch}, which only grows. A report with an older epoch than the one known is a late message and is
 * ignored; the same report twice changes nothing. That makes it safe to feed the gate from several sources at once (bridge messages and
 * database reads) in any order.
 */
public final class Gate {

	/** @param method null unless the player is authenticated */
	public record Entry(AuthState state, AuthMethod method, long epoch) {

		public boolean authenticated() {
			return this.state == AuthState.AUTHENTICATED;
		}
	}

	/** What a report did to the gate. */
	public enum Change {

		/** Older than what is known. */
		IGNORED,

		/** Nothing new. */
		UNCHANGED,

		/** The player is logged in now and was not before. */
		AUTHENTICATED,

		/** The player is not logged in now and was before. */
		DEAUTHENTICATED,

		/** The state changed but not whether the player may play (pending login became pending registration, say). */
		UPDATED
	}

	private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

	/** Only an authenticated player passes. */
	public boolean authenticated(UUID player) {
		final Entry entry = this.entries.get(player);

		return entry != null && entry.authenticated();
	}

	public Optional<Entry> get(UUID player) {
		return Optional.ofNullable(this.entries.get(player));
	}

	/**
	 * @param method must be set exactly when the state is {@link AuthState#AUTHENTICATED}
	 * @throws IllegalArgumentException if it is not
	 */
	public Change apply(UUID player, AuthState state, AuthMethod method, long epoch) {
		if ((state == AuthState.AUTHENTICATED) != (method != null))
			throw new IllegalArgumentException("an authenticated state needs a method, and any other must not have one");

		final Change[] change = new Change[1];

		this.entries.compute(player, (key, known) -> {
			if (known == null) {
				change[0] = state == AuthState.AUTHENTICATED ? Change.AUTHENTICATED : Change.UPDATED;

				return new Entry(state, method, epoch);
			}

			if (epoch < known.epoch()) {
				change[0] = Change.IGNORED;

				return known;
			}

			if (epoch == known.epoch() && state == known.state() && method == known.method()) {
				change[0] = Change.UNCHANGED;

				return known;
			}

			if (known.authenticated() == (state == AuthState.AUTHENTICATED))
				change[0] = Change.UPDATED;
			else
				change[0] = state == AuthState.AUTHENTICATED ? Change.AUTHENTICATED : Change.DEAUTHENTICATED;

			return new Entry(state, method, epoch);
		});

		return change[0];
	}

	/** Forgets the player: from now on they are not logged in. */
	public void remove(UUID player) {
		this.entries.remove(player);
	}

	public int size() {
		return this.entries.size();
	}

	public long authenticatedCount() {
		return this.entries.values().stream().filter(Entry::authenticated).count();
	}

	/** The players the gate knows, a copy. */
	public Set<UUID> known() {
		return Set.copyOf(this.entries.keySet());
	}

	/** For tests and status. */
	public Collection<Entry> entries() {
		return java.util.List.copyOf(this.entries.values());
	}
}
