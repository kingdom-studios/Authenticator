package pl.kingdomcraft.authenticator.common.auth;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository.Presence;

/**
 * Looks in the database for what changed behind the proxy's back, because an admin works on a backend server: an account removed, its
 * password or type changed, a player logged in or out by hand, a row of {@code auth_presence} lost. The database is the authority; a
 * message from a backend server ({@code ACCOUNT_CHANGED}) is only a prompt to look sooner, so a forged one can do no more than make
 * the proxy read a row.
 *
 * <p>Blocking: {@link #checkAll} and {@link #check} read the database and belong on a scheduler thread, never on an event thread.
 */
public final class StateWatcher {

	/** What the watcher could not settle by itself and the platform must act on. */
	public interface Handler {

		/** The player's account was removed. They are back to registering and must be taken to the waiting server. */
		void accountRemoved(PlayerSession session);

		/** The player's account changed type (premium to non-premium or back), so their connection no longer matches it: they must reconnect. */
		void typeChanged(PlayerSession session);

		/** An admin logged the player in or out; the state has been applied. */
		void stateForced(PlayerSession session);
	}

	private final AuthService auth;
	private final AccountRepository accounts;
	private final PresenceRepository presence;
	private final SessionManager sessions;
	private final String nodeId;
	private final Handler handler;
	private final Logger logger;

	private final AtomicLong removed = new AtomicLong();
	private final AtomicLong changed = new AtomicLong();
	private final AtomicLong forced = new AtomicLong();
	private final AtomicLong republished = new AtomicLong();
	private final AtomicLong ignored = new AtomicLong();

	public StateWatcher(AuthService auth, AccountRepository accounts, PresenceRepository presence, SessionManager sessions, String nodeId, Handler handler, Logger logger) {
		this.auth = auth;
		this.accounts = accounts;
		this.presence = presence;
		this.sessions = sessions;
		this.nodeId = nodeId;
		this.handler = handler;
		this.logger = logger;
	}

	/** Looks at every player on this proxy. A database error is logged and the next round tries again. */
	public void checkAll() {
		this.check(this.sessions.all());
	}

	/** Looks at one player, e.g. because a backend server said something about them. */
	public void check(UUID player) {
		this.sessions.find(player).ifPresent(session -> this.check(List.of(session)));
	}

	private void check(Collection<PlayerSession> toCheck) {
		if (toCheck.isEmpty())
			return;

		final List<UUID> uuids = new ArrayList<>(toCheck.size());
		final List<UUID> accountUuids = new ArrayList<>(toCheck.size());

		for (final PlayerSession session : toCheck) {
			uuids.add(session.uuid());

			if (session.account() != null)
				accountUuids.add(session.uuid());
		}

		final Map<UUID, Presence> rows;
		final Map<UUID, Long> versions;

		try {
			rows = this.presence.findAll(uuids);
			versions = this.accounts.versions(accountUuids);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not look for changes in the database: " + ex.getMessage());

			return;
		}

		for (final PlayerSession session : toCheck)
			try {
				this.check(session, rows.get(session.uuid()), versions);

			} catch (final RuntimeException ex) {
				this.logger.log(Level.WARNING, "Could not apply a change to " + session.username() + ": " + ex.getMessage(), ex);
			}
	}

	private void check(PlayerSession session, Presence row, Map<UUID, Long> versions) {
		// The player may have left, or reconnected, while the database was read: judge only the session that is still the current one.
		if (this.sessions.find(session.uuid()).orElse(null) != session)
			return;

		final Account account = session.account();

		if (account != null && !versions.containsKey(session.uuid())) {
			this.accountRemoved(session);

			return;
		}

		if (account != null && versions.get(session.uuid()) != account.version()) {
			this.accountChanged(session, account);

			if (this.sessions.find(session.uuid()).orElse(null) != session)
				return;
		}

		if (row == null) {
			// Someone deleted the row (an admin tidying up, a restore of an old backup): put it back, or the servers would think the player is unknown.
			this.republished.incrementAndGet();
			this.auth.republish(session);

			return;
		}

		// Only another node's row, written after the proxy's own last one, is a command; the proxy's own rows come with the epoch it has not yet stored.
		if (!row.proxyId().equals(this.nodeId) && row.epoch() > session.epoch())
			this.foreign(session, row);
	}

	private void accountRemoved(PlayerSession session) {
		this.removed.incrementAndGet();
		this.logger.info("The account of " + session.username() + " was removed; the player must register again.");

		this.auth.forceLoggedOut(session);
		this.handler.accountRemoved(session);
	}

	private void accountChanged(PlayerSession session, Account before) {
		this.changed.incrementAndGet();

		final Account after = this.auth.refresh(session).orElse(null);

		if (after != null && after.premium() != before.premium()) {
			this.logger.info("The account of " + session.username() + " became " + (after.premium() ? "premium" : "non-premium") + "; the player must reconnect.");
			this.handler.typeChanged(session);
		}
	}

	/** An admin wrote the player's state. Only a forced login or a logout is accepted: no other node has a reason to say anything else. */
	private void foreign(PlayerSession session, Presence row) {
		if (row.state() == AuthState.AUTHENTICATED && row.method() == AuthMethod.FORCED) {
			if (session.authenticated())
				return;

			this.forced.incrementAndGet();
			this.logger.info(session.username() + " was logged in by " + row.proxyId() + ".");
			this.auth.forceAuthenticated(session);
			this.handler.stateForced(session);

			return;
		}

		if (row.state() != AuthState.AUTHENTICATED) {
			if (!session.authenticated())
				return;

			this.forced.incrementAndGet();
			this.logger.info(session.username() + " was logged out by " + row.proxyId() + ".");
			this.auth.forceLoggedOut(session);
			this.handler.stateForced(session);

			return;
		}

		// "Authenticated" by premium login, password or session cannot come from another node; the proxy puts its own state back.
		this.ignored.incrementAndGet();
		this.logger.warning("Ignored a login state of " + session.username() + " written by " + row.proxyId() + " (" + row.method() + "): only a forced login is accepted from other nodes.");
		this.auth.republish(session);
	}

	/** Counts for /authadmin status. */
	public Map<String, Long> counters() {
		final Map<String, Long> counters = new HashMap<>();

		counters.put("accounts-removed", this.removed.get());
		counters.put("accounts-changed", this.changed.get());
		counters.put("states-forced", this.forced.get());
		counters.put("rows-restored", this.republished.get());
		counters.put("foreign-states-ignored", this.ignored.get());

		return counters;
	}
}
