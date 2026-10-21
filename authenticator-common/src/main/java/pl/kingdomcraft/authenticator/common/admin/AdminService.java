package pl.kingdomcraft.authenticator.common.admin;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.auth.PasswordPolicy;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository.Event;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository.Presence;
import pl.kingdomcraft.authenticator.common.repository.SessionRepository;

/**
 * What an admin can do to an account from a backend server. Every action is a change in the database, which is where the proxy looks
 * (within seconds, or at once when it is told); the caller may in addition tell the proxy with {@link Result#change()} to make that
 * immediate. All methods block (database, and for a password a hash): call them off the main thread.
 */
public final class AdminService {

	public enum Outcome {
		OK,
		NO_SUCH_ACCOUNT,

		/** The command is for password accounts. */
		PREMIUM_ACCOUNT,

		/** The command is for original accounts. */
		NOT_PREMIUM_ACCOUNT,
		WEAK_PASSWORD,

		/** The player is not connected to the network, so there is nothing to log in or out. */
		NOT_ONLINE,
		ALREADY_LOGGED_IN,
		NOT_LOGGED_IN,
		LOOKUP_FAILED,
		NOT_ORIGINAL_NAME,

		/** The account changed (or the Mojang account belongs to another one) while the command ran. */
		CONFLICT,
		ERROR
	}

	/**
	 * @param detail the {@link PasswordPolicy.Violation} for a weak password
	 * @param player the player concerned, to tell the proxy about; null when there is nothing to tell
	 * @param change what to tell it
	 */
	public record Result(Outcome outcome, String detail, UUID player, AccountChange change) {

		static Result of(Outcome outcome) {
			return new Result(outcome, "", null, null);
		}

		public boolean ok() {
			return this.outcome == Outcome.OK;
		}
	}

	/** An account and, if the player is on the network, how far they got. */
	public record Info(Account account, Presence presence) {
	}

	private static final int DUPLICATE_ENTRY = 1062;

	private final AccountRepository accounts;
	private final SessionRepository ipSessions;
	private final PresenceRepository presence;
	private final AuditRepository audit;
	private final PasswordHasher hasher;
	private final PasswordPolicy policy;
	private final PremiumResolver premium;
	private final String nodeId;
	private final Logger logger;

	public AdminService(AccountRepository accounts, SessionRepository ipSessions, PresenceRepository presence, AuditRepository audit, PasswordHasher hasher, PasswordPolicy policy, PremiumResolver premium,
			String nodeId, Logger logger) {
		this.accounts = accounts;
		this.ipSessions = ipSessions;
		this.presence = presence;
		this.audit = audit;
		this.hasher = hasher;
		this.policy = policy;
		this.premium = premium;
		this.nodeId = nodeId;
		this.logger = logger;
	}

	/** @return empty if there is no such account */
	public Optional<Info> info(String name) throws SQLException {
		final Optional<Account> account = this.accounts.findByUsername(name);

		if (account.isEmpty())
			return Optional.empty();

		return Optional.of(new Info(account.get(), this.presence.find(account.get().uuid()).orElse(null)));
	}

	/** Removes the account. The player, if online, is sent back to registering by the proxy. */
	public Result unregister(String name, String actor) {
		return this.run(() -> {
			final Account account = this.accounts.findByUsername(name).orElse(null);

			if (account == null)
				return Result.of(Outcome.NO_SUCH_ACCOUNT);

			if (!this.accounts.delete(account.id()))
				return Result.of(Outcome.CONFLICT);

			this.record(Event.UNREGISTER, account, actor, "by an admin");

			return new Result(Outcome.OK, "", account.uuid(), AccountChange.REMOVED);
		});
	}

	/** Sets a new password. Other addresses that were logged in have to type it. */
	public Result changePassword(String name, String password, String actor) {
		return this.run(() -> {
			final Account account = this.accounts.findByUsername(name).orElse(null);

			if (account == null)
				return Result.of(Outcome.NO_SUCH_ACCOUNT);

			if (account.premium())
				return Result.of(Outcome.PREMIUM_ACCOUNT);

			final Result weak = this.weak(account, password);

			if (weak != null)
				return weak;

			final PasswordHasher.Hashed hashed = this.hasher.hash(password);

			if (!this.accounts.updatePassword(account.id(), account.version(), hashed.hash(), hashed.algorithm()))
				return Result.of(Outcome.CONFLICT);

			this.ipSessions.delete(account.id());
			this.record(Event.PASSWORD_CHANGED, account, actor, "by an admin");

			return new Result(Outcome.OK, "", account.uuid(), AccountChange.PASSWORD);
		});
	}

	/** Logs in a player who is waiting to. */
	public Result forceLogin(String name, String actor) {
		return this.run(() -> {
			final Account account = this.accounts.findByUsername(name).orElse(null);

			if (account == null)
				return Result.of(Outcome.NO_SUCH_ACCOUNT);

			final Presence row = this.presence.find(account.uuid()).orElse(null);

			if (row == null)
				return Result.of(Outcome.NOT_ONLINE);

			if (row.state() == AuthState.AUTHENTICATED)
				return Result.of(Outcome.ALREADY_LOGGED_IN);

			this.presence.save(account.uuid(), account.id(), AuthState.AUTHENTICATED, AuthMethod.FORCED, this.nodeId);
			this.record(Event.FORCED_LOGIN, account, actor, null);

			return new Result(Outcome.OK, "", account.uuid(), AccountChange.FORCED_LOGIN);
		});
	}

	/** Makes a logged in player type the password again. */
	public Result forceLogout(String name, String actor) {
		return this.run(() -> {
			final Account account = this.accounts.findByUsername(name).orElse(null);

			if (account == null)
				return Result.of(Outcome.NO_SUCH_ACCOUNT);

			if (account.premium())
				return Result.of(Outcome.PREMIUM_ACCOUNT); // an original account has no password to type: kick the player instead

			final Presence row = this.presence.find(account.uuid()).orElse(null);

			if (row == null)
				return Result.of(Outcome.NOT_ONLINE);

			if (row.state() != AuthState.AUTHENTICATED)
				return Result.of(Outcome.NOT_LOGGED_IN);

			this.presence.save(account.uuid(), account.id(), AuthState.PENDING_LOGIN, null, this.nodeId);
			this.record(Event.FORCED_LOGOUT, account, actor, null);

			return new Result(Outcome.OK, "", account.uuid(), AccountChange.FORCED_LOGOUT);
		});
	}

	/** Turns a password account into an original one, after Mojang says the name is an original name. The player has to reconnect. */
	public Result setPremium(String name, String actor) {
		return this.run(() -> {
			final Account account = this.accounts.findByUsername(name).orElse(null);

			if (account == null)
				return Result.of(Outcome.NO_SUCH_ACCOUNT);

			if (account.premium())
				return Result.of(Outcome.PREMIUM_ACCOUNT);

			this.premium.forget(account.username()); // ask the services again, not the cache

			final PremiumResolver.Result lookup = this.premium.resolve(account.username());

			switch (lookup.status()) {
				case UNKNOWN:
					return Result.of(Outcome.LOOKUP_FAILED);

				case CRACKED:
					return Result.of(Outcome.NOT_ORIGINAL_NAME);

				default:
					break;
			}

			if (!this.accounts.setPremium(account.id(), account.version(), lookup.uuid()))
				return Result.of(Outcome.CONFLICT);

			this.ipSessions.delete(account.id());
			this.record(Event.PREMIUM_ENABLED, account, actor, lookup.uuid().toString());

			return new Result(Outcome.OK, "", account.uuid(), AccountChange.TYPE);
		});
	}

	/** Turns an original account into a password account. The player has to reconnect and type the password. */
	public Result setCracked(String name, String password, String actor) {
		return this.run(() -> {
			final Account account = this.accounts.findByUsername(name).orElse(null);

			if (account == null)
				return Result.of(Outcome.NO_SUCH_ACCOUNT);

			if (!account.premium())
				return Result.of(Outcome.NOT_PREMIUM_ACCOUNT);

			final Result weak = this.weak(account, password);

			if (weak != null)
				return weak;

			final PasswordHasher.Hashed hashed = this.hasher.hash(password);

			if (!this.accounts.setCracked(account.id(), account.version(), hashed.hash(), hashed.algorithm()))
				return Result.of(Outcome.CONFLICT);

			this.record(Event.CRACKED_ENABLED, account, actor, "by an admin");

			return new Result(Outcome.OK, "", account.uuid(), AccountChange.TYPE);
		});
	}

	private Result weak(Account account, String password) {
		final Optional<PasswordPolicy.Violation> violation = this.policy.check(account.username(), password);

		return violation.map(value -> new Result(Outcome.WEAK_PASSWORD, value == PasswordPolicy.Violation.TOO_SHORT ? value.name() + ":" + this.policy.minLength() : value.name(), null, null)).orElse(null);
	}

	/** The audit trail is good to have but must not undo an action that already happened. */
	private void record(Event event, Account account, String actor, String details) {
		try {
			this.audit.log(event, account.uuid(), account.username(), (InetAddress) null, actor, this.nodeId, details);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not write the admin action to the audit trail: " + ex.getMessage());
		}
	}

	@FunctionalInterface
	private interface Action {
		Result run() throws SQLException;
	}

	private Result run(Action action) {
		try {
			return action.run();

		} catch (final AccountRepository.DuplicateAccountException ex) {
			return Result.of(Outcome.CONFLICT);

		} catch (final SQLException ex) {
			if (ex.getErrorCode() == DUPLICATE_ENTRY) // e.g. the Mojang account is already another account's
				return Result.of(Outcome.CONFLICT);

			this.logger.log(Level.WARNING, "An admin action failed: " + ex.getMessage(), ex);

			return Result.of(Outcome.ERROR);

		} catch (final RuntimeException ex) {
			this.logger.log(Level.WARNING, "An admin action failed: " + ex.getMessage(), ex);

			return Result.of(Outcome.ERROR);
		}
	}
}
