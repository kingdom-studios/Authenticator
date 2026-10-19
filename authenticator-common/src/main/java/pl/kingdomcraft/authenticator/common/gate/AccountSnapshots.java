package pl.kingdomcraft.authenticator.common.gate;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;

/**
 * What placeholders say about a player, kept in memory and filled in the background. A placeholder can be asked for many times a tick on the
 * main thread (scoreboards, tab lists), so it must never wait for the database: it reads {@link #peek}, and a missing value is shown as
 * "loading" until {@link #refresh} has run.
 */
public final class AccountSnapshots {

	/** @param registered whether the player has an account at all */
	public record Snapshot(boolean registered, boolean premium) {
	}

	private final AccountRepository accounts;
	private final Executor executor;
	private final Logger logger;
	private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

	public AccountSnapshots(AccountRepository accounts, Executor executor, Logger logger) {
		this.accounts = accounts;
		this.executor = executor;
		this.logger = logger;
	}

	/** @return the last snapshot, or empty if none has been taken yet */
	public Optional<Snapshot> peek(UUID player) {
		return Optional.ofNullable(this.snapshots.get(player));
	}

	/** Takes a new snapshot in the background. */
	public void refresh(UUID player) {
		try {
			this.executor.execute(() -> this.load(player));

		} catch (final RuntimeException ex) {
			// shutting down
		}
	}

	/** Takes a snapshot on the calling thread. For tests. */
	public void load(UUID player) {
		try {
			final Optional<Account> account = this.accounts.findByUuid(player);

			this.snapshots.put(player, new Snapshot(account.isPresent(), account.map(Account::premium).orElse(false)));

		} catch (final SQLException | RuntimeException ex) {
			this.logger.fine("Could not take a snapshot of " + player + ": " + ex.getMessage());
		}
	}

	public void forget(UUID player) {
		this.snapshots.remove(player);
	}
}
