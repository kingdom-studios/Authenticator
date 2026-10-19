package pl.kingdomcraft.authenticator.common.gate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository.Presence;

/**
 * Keeps the {@link Gate} true to what the proxy has written down. Two sources feed it and neither needs the other:
 * <ul>
 * <li>the bridge ({@link #apply}), which is immediate but may be lost;</li>
 * <li>the database ({@code auth_presence}), read when a player connects and then every {@code poll} for everyone online here, which is
 * never lost.</li>
 * </ul>
 * So a lost message delays a player by one poll and never leaves a state wrong for long, and a server with no bridge at all works the same,
 * only a little slower.
 *
 * <p>Nothing here waits on a tick: the platform is told through the {@link Listener}, on a thread of this class, and moves to its own thread
 * itself.
 */
public final class JoinVerifier implements AutoCloseable {

	/** What this server is for. */
	public enum Role {

		/** The waiting server: lets in players who have not logged in yet, and keeps them frozen. */
		AUTH,

		/** A server where the game is played: lets in only players who have logged in. */
		GAME
	}

	public enum Reason {
		OK,

		/** A {@link Role#GAME} server and the player has not logged in. */
		NOT_AUTHENTICATED,

		/** The proxy has no record of the player, so they did not come through it. */
		UNKNOWN_TO_PROXY,

		/** The database cannot be read, so nothing can be said: refused. */
		DATABASE_ERROR
	}

	public record LoginDecision(boolean allowed, Reason reason) {
	}

	/** Told when a player's right to play changes. Called on a thread of the verifier; do not block in it. */
	public interface Listener {

		void authenticated(UUID player);

		void deauthenticated(UUID player);
	}

	private final PresenceRepository presence;
	private final Gate gate;
	private final Role role;
	private final Listener listener;
	private final Logger logger;
	private final Set<UUID> online = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "Authenticator-Gate");
		thread.setDaemon(true);

		return thread;
	});
	private final AtomicLong lastFailureLog = new AtomicLong();

	/** @param poll how often the database is read for the players who are here */
	public JoinVerifier(PresenceRepository presence, Gate gate, Role role, Duration poll, Listener listener, Logger logger) {
		if (poll.toMillis() < 200)
			throw new IllegalArgumentException("poll must be at least 200 ms");

		this.presence = presence;
		this.gate = gate;
		this.role = role;
		this.listener = listener;
		this.logger = logger;

		this.executor.scheduleWithFixedDelay(this::pollQuietly, poll.toMillis(), poll.toMillis(), TimeUnit.MILLISECONDS);
	}

	public Role role() {
		return this.role;
	}

	public Gate gate() {
		return this.gate;
	}

	/**
	 * Decides, before the player is let in, whether this server takes them. Reads the database; call it where blocking is allowed (the
	 * asynchronous pre-login event). Whatever was known of an earlier connection of the same player is forgotten first.
	 */
	public LoginDecision checkLogin(UUID player) {
		this.gate.remove(player);

		final Optional<Presence> row;

		try {
			row = this.presence.find(player);

		} catch (final SQLException | RuntimeException ex) {
			this.logFailure("Could not read the login state of " + player + ", so the player is refused: " + ex.getMessage());

			return new LoginDecision(false, Reason.DATABASE_ERROR);
		}

		if (row.isEmpty())
			return new LoginDecision(false, Reason.UNKNOWN_TO_PROXY);

		final Presence state = row.get();

		if (this.role == Role.GAME && state.state() != AuthState.AUTHENTICATED)
			return new LoginDecision(false, Reason.NOT_AUTHENTICATED);

		this.gate.apply(player, state.state(), state.method(), state.epoch());

		return new LoginDecision(true, Reason.OK);
	}

	/** The player is on this server now: from here on the database is watched for them. */
	public void joined(UUID player) {
		this.online.add(player);
		this.executor.execute(() -> this.refresh(Set.of(player)));
	}

	/** The player left. */
	public void left(UUID player) {
		this.online.remove(player);
		this.gate.remove(player);
	}

	/** A message from the proxy. The platform has already checked its signature. */
	public void apply(AuthStateMessage message) {
		// A message about someone who is not here, and was not let in here, is for another server. One that arrives a moment before the join
		// event is still about our player: the pre-login check has seeded the gate for them.
		if (!this.online.contains(message.player()) && this.gate.get(message.player()).isEmpty())
			return;

		this.notify(message.player(), this.gate.apply(message.player(), message.state(), message.method(), message.epoch()));
	}

	private void pollQuietly() {
		try {
			this.refresh(this.online);

		} catch (final RuntimeException ex) {
			this.logFailure("Looking at the login states failed: " + ex.getMessage());
		}
	}

	private void refresh(Set<UUID> players) {
		if (players.isEmpty())
			return;

		final Map<UUID, Presence> rows;

		try {
			rows = this.presence.findAll(Set.copyOf(players));

		} catch (final SQLException | RuntimeException ex) {
			this.logFailure("Could not read the login states of the players here; they stay as they are: " + ex.getMessage());

			return;
		}

		for (final UUID player : players) {
			final Presence row = rows.get(player);

			// No row means the proxy has said nothing (it may be restarting): it neither logs anyone in nor out.
			if (row != null && this.online.contains(player))
				this.notify(player, this.gate.apply(player, row.state(), row.method(), row.epoch()));
		}
	}

	private void notify(UUID player, Gate.Change change) {
		try {
			if (change == Gate.Change.AUTHENTICATED)
				this.listener.authenticated(player);
			else if (change == Gate.Change.DEAUTHENTICATED)
				this.listener.deauthenticated(player);

		} catch (final RuntimeException ex) {
			this.logger.log(Level.WARNING, "A login listener failed for " + player, ex);
		}
	}

	/** A database that is down fails every poll; say so once a minute, not every two seconds. */
	private void logFailure(String message) {
		final long now = System.currentTimeMillis();
		final long last = this.lastFailureLog.get();

		if (now - last >= 60_000 && this.lastFailureLog.compareAndSet(last, now))
			this.logger.warning(message);
	}

	/** Reads the database for these players now, on the calling thread. For tests and for a player who has just joined. */
	public void refreshNow(Set<UUID> players) {
		this.refresh(players);
	}

	@Override
	public void close() {
		this.executor.shutdownNow();
	}
}
