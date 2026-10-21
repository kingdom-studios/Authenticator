package pl.kingdomcraft.authenticator.common.auth;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A connected player's login state on the proxy. The state changes only through the methods here, all of which are synchronized, so a
 * command, a timeout and a message from a backend server can never leave it half changed.
 */
public final class PlayerSession {

	private final UUID uuid;
	private final String username;
	private final InetAddress address;
	private final Instant connectedAt;
	private final Instant deadline;
	private final boolean premiumConnection;
	private final AtomicBoolean busy = new AtomicBoolean();

	private AuthState state;
	private AuthMethod method;
	private Account account;
	private long epoch;
	private int failedAttempts;
	private String confirmKind;
	private Instant confirmUntil;

	/**
	 * @param deadline          when a player who is still not authenticated is kicked
	 * @param premiumConnection whether Mojang confirmed this connection (online mode)
	 * @param account           null for a new player who has not registered
	 */
	public PlayerSession(UUID uuid, String username, InetAddress address, Instant connectedAt, Instant deadline, boolean premiumConnection, AuthState state, AuthMethod method, Account account) {
		this.uuid = uuid;
		this.username = username;
		this.address = address;
		this.connectedAt = connectedAt;
		this.deadline = deadline;
		this.premiumConnection = premiumConnection;
		this.state = state;
		this.method = method;
		this.account = account;
	}

	public UUID uuid() {
		return this.uuid;
	}

	public String username() {
		return this.username;
	}

	public InetAddress address() {
		return this.address;
	}

	public Instant connectedAt() {
		return this.connectedAt;
	}

	public boolean premiumConnection() {
		return this.premiumConnection;
	}

	public synchronized AuthState state() {
		return this.state;
	}

	public synchronized AuthMethod method() {
		return this.method;
	}

	/** The player's account, or null for a new player who has not registered. */
	public synchronized Account account() {
		return this.account;
	}

	/** Counts up with every change of state; the same number as in {@code auth_presence} once it has been written. */
	public synchronized long epoch() {
		return this.epoch;
	}

	public synchronized boolean authenticated() {
		return this.state == AuthState.AUTHENTICATED;
	}

	/** @return true if the player is still waiting to authenticate and their time is up */
	public synchronized boolean expired(Instant now) {
		return this.state != AuthState.AUTHENTICATED && !now.isBefore(this.deadline);
	}

	public synchronized void authenticate(AuthMethod method, Account account) {
		this.state = AuthState.AUTHENTICATED;
		this.method = method;
		this.account = account;
		this.failedAttempts = 0;
		this.clearConfirmation();
	}

	/** The account changed (a new password, another type); the state stays. */
	public synchronized void updateAccount(Account account) {
		this.account = account;
	}

	/** Back to waiting, e.g. when an admin logs the player out or removes the account. */
	public synchronized void reset(AuthState state, Account account) {
		this.state = state;
		this.method = null;
		this.account = account;
		this.clearConfirmation();
	}

	public synchronized void epoch(long epoch) {
		this.epoch = epoch;
	}

	/** @return how many wrong passwords this visit has had, this one included */
	public synchronized int recordFailure() {
		return ++this.failedAttempts;
	}

	public synchronized int failedAttempts() {
		return this.failedAttempts;
	}

	/**
	 * Two-step confirmation for destructive commands: the first call arms it, a second call of the same kind within the window confirms.
	 *
	 * @return true if this call confirms an earlier one
	 */
	public synchronized boolean confirm(String kind, Instant now, java.time.Duration window) {
		if (kind.equals(this.confirmKind) && this.confirmUntil != null && now.isBefore(this.confirmUntil)) {
			this.clearConfirmation();

			return true;
		}

		this.confirmKind = kind;
		this.confirmUntil = now.plus(window);

		return false;
	}

	private void clearConfirmation() {
		this.confirmKind = null;
		this.confirmUntil = null;
	}

	/** Takes the single slot for a command that changes the account, so two of them cannot run at once. @return false if one is already running */
	public boolean tryBegin() {
		return this.busy.compareAndSet(false, true);
	}

	public void end() {
		this.busy.set(false);
	}

	@Override
	public String toString() {
		return "PlayerSession[" + this.username + ", " + this.state() + "]";
	}
}
