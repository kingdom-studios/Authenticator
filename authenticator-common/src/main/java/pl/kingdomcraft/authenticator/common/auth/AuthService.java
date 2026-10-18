package pl.kingdomcraft.authenticator.common.auth;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.hash.HashPool;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;
import pl.kingdomcraft.authenticator.common.limit.RateLimiter;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.DuplicateAccountException;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository.Event;
import pl.kingdomcraft.authenticator.common.repository.IpBlockRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.SessionRepository;
import pl.kingdomcraft.authenticator.common.settings.LimitSettings;
import pl.kingdomcraft.authenticator.common.settings.LoginSettings;

/**
 * Everything a player can do to their login: connect, register, log in, change the password, leave, switch between premium and
 * non-premium. It is platform-free; the proxy turns the results into messages, kicks and server moves.
 *
 * <p>{@link #profileUuid} and {@link #open} are blocking and belong in an asynchronous event task. The commands return futures: the
 * database work runs on the database executor and every hash on the {@link HashPool}, so neither blocks the other nor the proxy.
 * Only one command that changes an account runs per player at a time.
 */
public final class AuthService {

	/** The result of a command. */
	public enum Outcome {

		OK,

		/** Done, and the player must reconnect for it to take effect (the proxy kicks them with a message). */
		OK_RECONNECT,

		WRONG_PASSWORD,

		/** The player used up their attempts, or the address its allowance: the proxy kicks them. */
		TOO_MANY_ATTEMPTS,

		IP_BLOCKED,

		NOT_LOGGED_IN,
		ALREADY_LOGGED_IN,
		ALREADY_REGISTERED,
		NOT_REGISTERED,
		PASSWORD_MISMATCH,
		WEAK_PASSWORD,
		TOO_MANY_ACCOUNTS,
		NAME_TAKEN,

		/** The command is for non-premium accounts, which have a password. */
		PREMIUM_ACCOUNT,

		/** The command is for premium accounts. */
		NOT_PREMIUM_ACCOUNT,

		/** Mojang says no original account has this name. */
		NOT_ORIGINAL_NAME,

		/** No lookup service answered. */
		LOOKUP_FAILED,

		/** Armed; the same command again within the window does it. */
		CONFIRM_REQUIRED,

		/** Another command of this player is still running, or hashing is saturated. */
		BUSY,

		/** The account changed while the command ran. */
		CONFLICT,

		ERROR
	}

	/**
	 * @param detail extra for the message: the attempts left for {@link Outcome#WRONG_PASSWORD}, the {@link PasswordPolicy.Violation} for
	 *               {@link Outcome#WEAK_PASSWORD}, the limit for {@link Outcome#TOO_MANY_ACCOUNTS}, until when for {@link Outcome#IP_BLOCKED}
	 */
	public record Result(Outcome outcome, String detail) {

		static Result of(Outcome outcome) {
			return new Result(outcome, "");
		}

		public boolean ok() {
			return this.outcome == Outcome.OK || this.outcome == Outcome.OK_RECONNECT;
		}
	}

	public enum OpenFailure {

		DATABASE_ERROR,

		/** The name belongs to another original account (its owner changed names and someone took the old one). */
		NAME_OWNED_BY_OTHER,

		/** An original connection for a name registered with a password, or the other way round. */
		ACCOUNT_TYPE_MISMATCH,

		/** An original player changed their name to one that is registered. */
		RENAME_CONFLICT,

		/** Mojang's UUID of an online connection is not known, so ownership cannot be checked. */
		UNVERIFIED_PROFILE
	}

	/** @param session set when the player may stay; @param failure set when they must be refused */
	public record Opened(PlayerSession session, OpenFailure failure) {
	}

	private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);

	private final Database database;
	private final AccountRepository accounts;
	private final SessionRepository ipSessions;
	private final PresenceRepository presence;
	private final AuditRepository audit;
	private final IpBlockRepository blocks;
	private final PasswordHasher hasher;
	private final HashPool hashPool;
	private final PasswordPolicy policy;
	private final LoginSettings login;
	private final LimitSettings limits;
	private final SessionManager sessions;
	private final PreLoginDecider decider;
	private final PremiumResolver premium;
	private final Clock clock;
	private final String nodeId;
	private final Logger logger;
	private final RateLimiter<InetAddress> wrongPasswords;

	/** Mojang's UUID of players who just passed online authentication, until their session is opened a moment later. */
	private final Cache<String, UUID> mojangUuids;

	private volatile StateListener listener = session -> {
	};

	public AuthService(Database database, AccountRepository accounts, SessionRepository ipSessions, PresenceRepository presence, AuditRepository audit, IpBlockRepository blocks, PasswordHasher hasher,
			HashPool hashPool, PasswordPolicy policy, LoginSettings login, LimitSettings limits, SessionManager sessions, PreLoginDecider decider, PremiumResolver premium, Clock clock, String nodeId,
			Logger logger) {
		this.database = database;
		this.accounts = accounts;
		this.ipSessions = ipSessions;
		this.presence = presence;
		this.audit = audit;
		this.blocks = blocks;
		this.hasher = hasher;
		this.hashPool = hashPool;
		this.policy = policy;
		this.login = login;
		this.limits = limits;
		this.sessions = sessions;
		this.decider = decider;
		this.premium = premium;
		this.clock = clock;
		this.nodeId = nodeId;
		this.logger = logger;
		this.wrongPasswords = new RateLimiter<>(limits.attemptsPerIp(), limits.attemptsWindow(), clock);
		this.mojangUuids = Caffeine.newBuilder().maximumSize(10_000).ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).expireAfterWrite(Duration.ofSeconds(60)).build();
	}

	public void setStateListener(StateListener listener) {
		this.listener = listener;
	}

	public SessionManager sessions() {
		return this.sessions;
	}

	private Instant now() {
		return Instant.ofEpochMilli(this.clock.millis());
	}

	// ------------------------------------------------------------------------------------------
	// Connecting
	// ------------------------------------------------------------------------------------------

	/**
	 * The UUID the player must have on the servers. A registered player keeps the UUID of their account whatever kind of connection they
	 * come with; a new player gets the one Velocity made. Only an original connection proven by Mojang can take over an account that
	 * belongs to an original account, and only through the Mojang UUID: a name alone never maps to someone else's UUID.
	 */
	public UUID profileUuid(String username, boolean online, UUID profileUuid) {
		try {
			if (online) {
				this.mojangUuids.put(Account.key(username), profileUuid);

				final Optional<Account> account = this.accounts.findByPremiumUuid(profileUuid);

				return account.isPresent() ? account.get().uuid() : profileUuid;
			}

			final Optional<Account> account = this.accounts.findByUsername(username);

			return account.isPresent() && !account.get().premium() ? account.get().uuid() : profileUuid;

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not look up the account of " + username + " to choose its UUID: " + ex.getMessage());

			return profileUuid;
		}
	}

	/**
	 * Opens the session of a player who has just connected: an original player is authenticated at once, a non-premium one with a valid
	 * session from the same address too, anyone else must log in or register first. Creates the account of a new original player.
	 */
	public Opened open(String username, UUID uuid, InetAddress address, boolean online) {
		try {
			return online ? this.openOnline(username, uuid, address) : this.openOffline(username, uuid, address);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not open the session of " + username + ": " + ex.getMessage());

			return new Opened(null, OpenFailure.DATABASE_ERROR);
		}
	}

	private Opened openOnline(String username, UUID uuid, InetAddress address) throws SQLException {
		final UUID mojang = this.mojangUuids.getIfPresent(Account.key(username));

		if (mojang == null)
			return new Opened(null, OpenFailure.UNVERIFIED_PROFILE);

		Account account = this.accounts.findByPremiumUuid(mojang).orElse(null);

		if (account == null) {
			final Optional<Account> byName = this.accounts.findByUsername(username);

			if (byName.isPresent())
				return new Opened(null, byName.get().premium() ? OpenFailure.NAME_OWNED_BY_OTHER : OpenFailure.ACCOUNT_TYPE_MISMATCH);

			try {
				account = this.accounts.insert(new NewAccount(uuid, username, true, mojang, null, null, null, address, this.now(), null, null));

			} catch (final DuplicateAccountException ex) {
				return new Opened(null, OpenFailure.NAME_OWNED_BY_OTHER); // someone took the name in the meantime
			}

		} else if (!account.username().equals(username)) {
			final Account before = account;

			try {
				this.accounts.rename(before.id(), before.version(), username);
				this.bestEffort(() -> this.audit.log(Event.ACCOUNT_RENAMED, before.uuid(), username, address, null, this.nodeId, "was " + before.username()));

			} catch (final DuplicateAccountException ex) {
				return new Opened(null, OpenFailure.RENAME_CONFLICT);
			}

			account = this.accounts.findById(before.id()).orElse(before);
		}

		this.mojangUuids.invalidate(Account.key(username));
		this.decider.claimSucceeded(username, address);
		this.accounts.recordLogin(account.id(), address, this.now());

		final PlayerSession session = this.newSession(uuid, username, address, true, AuthState.AUTHENTICATED, AuthMethod.PREMIUM, account);

		this.publish(session);
		this.bestEffort(() -> this.audit.log(Event.LOGIN_SUCCESS, uuid, username, address, null, this.nodeId, "premium"));

		return new Opened(session, null);
	}

	private Opened openOffline(String username, UUID uuid, InetAddress address) throws SQLException {
		final Optional<Account> existing = this.accounts.findByUsername(username);

		if (existing.isEmpty()) {
			final PlayerSession session = this.newSession(uuid, username, address, false, AuthState.PENDING_REGISTER, null, null);

			this.publish(session);

			return new Opened(session, null);
		}

		final Account account = existing.get();

		if (account.premium())
			return new Opened(null, OpenFailure.ACCOUNT_TYPE_MISMATCH);

		if (this.login.sessionsEnabled() && this.ipSessions.findValid(account.id(), address, this.now()).isPresent()) {
			this.accounts.recordLogin(account.id(), address, this.now());

			final PlayerSession session = this.newSession(uuid, username, address, false, AuthState.AUTHENTICATED, AuthMethod.SESSION, account);

			this.publish(session);
			this.bestEffort(() -> this.audit.log(Event.LOGIN_SUCCESS, uuid, username, address, null, this.nodeId, "session"));

			return new Opened(session, null);
		}

		final PlayerSession session = this.newSession(uuid, username, address, false, AuthState.PENDING_LOGIN, null, account);

		this.publish(session);

		return new Opened(session, null);
	}

	private PlayerSession newSession(UUID uuid, String username, InetAddress address, boolean online, AuthState state, AuthMethod method, Account account) {
		final Instant now = this.now();
		final PlayerSession session = new PlayerSession(uuid, username, address, now, now.plus(this.login.timeout()), online, state, method, account);

		this.sessions.add(session);

		return session;
	}

	/** The player left. Their row is removed unless something changed it after the proxy last wrote it. */
	public void close(PlayerSession session) {
		this.sessions.remove(session);

		this.bestEffort(() -> this.presence.deleteIfUnchanged(session.uuid(), session.epoch()));
	}

	/** Writes the state to the database, then tells the platform. Never throws: the database being down must not break a login that already succeeded. */
	private void publish(PlayerSession session) {
		try {
			final Account account = session.account();

			session.epoch(this.presence.save(session.uuid(), account == null ? null : account.id(), session.state(), session.method(), this.nodeId));

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not write the login state of " + session.username() + " to the database; servers may only learn it from a message: " + ex.getMessage());
		}

		try {
			this.listener.stateChanged(session);

		} catch (final RuntimeException ex) {
			this.logger.log(Level.WARNING, "A state listener failed for " + session.username(), ex);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Commands
	// ------------------------------------------------------------------------------------------

	public CompletableFuture<Result> register(PlayerSession session, String password, String confirmation) {
		return this.guarded(session, () -> {
			final Result state = this.requireState(session, AuthState.PENDING_REGISTER);

			if (state != null)
				return done(state);

			if (!password.equals(confirmation))
				return done(Result.of(Outcome.PASSWORD_MISMATCH));

			final Result weak = this.weak(session, password);

			if (weak != null)
				return done(weak);

			return this.database.supplyAsync(() -> this.registrationLimit(session)).thenCompose(refused -> {
				if (refused != null)
					return done(refused);

				return this.hashPool.submit(() -> this.hasher.hash(password)).thenCompose(hashed -> this.database.supplyAsync(() -> this.createAccount(session, hashed)));
			});
		});
	}

	private Result registrationLimit(PlayerSession session) throws SQLException {
		if (this.limits.accountsPerIp() > 0 && this.accounts.countRegisteredFrom(session.address(), this.now().minus(this.limits.accountsWindow())) >= this.limits.accountsPerIp())
			return new Result(Outcome.TOO_MANY_ACCOUNTS, String.valueOf(this.limits.accountsPerIp()));

		return null;
	}

	private Result createAccount(PlayerSession session, PasswordHasher.Hashed hashed) throws SQLException {
		final Account account;

		try {
			account = this.accounts.insert(new NewAccount(session.uuid(), session.username(), false, null, hashed.hash(), null, hashed.algorithm(), session.address(), this.now(), null, null));

		} catch (final DuplicateAccountException ex) {
			return Result.of(Outcome.NAME_TAKEN);
		}

		this.finishLogin(session, AuthMethod.PASSWORD, account);
		this.bestEffort(() -> this.audit.log(Event.REGISTER, session.uuid(), session.username(), session.address(), null, this.nodeId, null));

		return Result.of(Outcome.OK);
	}

	public CompletableFuture<Result> login(PlayerSession session, String password) {
		return this.guarded(session, () -> {
			final Result state = this.requireState(session, AuthState.PENDING_LOGIN);

			if (state != null)
				return done(state);

			return this.database.supplyAsync(() -> this.loginPrecheck(session)).thenCompose(pre -> {
				if (pre.refused() != null)
					return done(pre.refused());

				return this.verify(pre.account(), password).thenCompose(correct -> this.database.supplyAsync(() -> correct ? this.loginSucceeded(session, pre.account(), password) : this.wrongPassword(session)));
			});
		});
	}

	private record Precheck(Result refused, Account account) {
	}

	private Precheck loginPrecheck(PlayerSession session) throws SQLException {
		final Optional<Instant> blockedUntil = this.blocks.blockedUntil(session.address(), this.now());

		if (blockedUntil.isPresent())
			return new Precheck(new Result(Outcome.IP_BLOCKED, blockedUntil.get().toString()), null);

		final Account known = session.account();
		final Optional<Account> fresh = known == null ? Optional.empty() : this.accounts.findById(known.id()); // it may have been changed by an admin meanwhile

		if (fresh.isEmpty())
			return new Precheck(Result.of(Outcome.NOT_REGISTERED), null);

		return fresh.get().hasPassword() ? new Precheck(null, fresh.get()) : new Precheck(Result.of(Outcome.PREMIUM_ACCOUNT), null);
	}

	private CompletableFuture<Boolean> verify(Account account, String password) {
		return this.hashPool.submit(() -> this.hasher.verify(account.hashAlgo(), account.passwordHash(), account.passwordSalt(), password));
	}

	private Result loginSucceeded(PlayerSession session, Account account, String password) throws SQLException {
		this.wrongPasswords.reset(session.address());
		this.finishLogin(session, AuthMethod.PASSWORD, account);
		this.bestEffort(() -> this.audit.log(Event.LOGIN_SUCCESS, session.uuid(), session.username(), session.address(), null, this.nodeId, "password"));

		if (account.hashAlgo() == null || this.hasher.needsRehash(account.hashAlgo(), account.passwordHash()))
			this.upgradeHash(session, account, password);

		return Result.of(Outcome.OK);
	}

	/** Replaces an old or weak hash with a fresh one, in the background: the login has already succeeded and must not wait for it. */
	private void upgradeHash(PlayerSession session, Account account, String password) {
		this.hashPool.submit(() -> this.hasher.hash(password)).thenAcceptAsync(hashed -> {
			try {
				if (this.accounts.updatePassword(account.id(), account.version(), hashed.hash(), hashed.algorithm())) {
					this.accounts.findById(account.id()).ifPresent(session::updateAccount);
					this.bestEffort(() -> this.audit.log(Event.PASSWORD_UPGRADED, session.uuid(), session.username(), session.address(), null, this.nodeId,
							(account.hashAlgo() == null ? "?" : account.hashAlgo().name()) + " -> " + hashed.algorithm().name()));
				}

			} catch (final SQLException ex) {
				this.logger.log(Level.WARNING, "Could not replace the old password hash of " + session.username() + ": " + ex.getMessage());
			}
		}, this.database.executor()).exceptionally(ex -> {
			this.logger.log(Level.FINE, "Could not replace the old password hash of " + session.username() + ": " + ex);

			return null;
		});
	}

	private Result wrongPassword(PlayerSession session) throws SQLException {
		final int failures = session.recordFailure();
		final int fromAddress = this.wrongPasswords.hit(session.address());

		this.bestEffort(() -> this.audit.log(Event.LOGIN_FAILED, session.uuid(), session.username(), session.address(), null, this.nodeId, failures + "/" + this.login.maxAttempts()));

		if (fromAddress >= this.limits.attemptsPerIp()) {
			final Instant until = this.now().plus(this.limits.ipBlock());

			this.blocks.block(session.address(), until, "too many wrong passwords");
			this.decider.noteBlocked(session.address(), until);
			this.bestEffort(() -> this.audit.log(Event.IP_BLOCKED, session.uuid(), session.username(), session.address(), null, this.nodeId, "until " + until));

			return new Result(Outcome.IP_BLOCKED, until.toString());
		}

		if (failures >= this.login.maxAttempts())
			return Result.of(Outcome.TOO_MANY_ATTEMPTS);

		return new Result(Outcome.WRONG_PASSWORD, String.valueOf(this.login.maxAttempts() - failures));
	}

	private void finishLogin(PlayerSession session, AuthMethod method, Account account) throws SQLException {
		this.accounts.recordLogin(account.id(), session.address(), this.now());

		if (this.login.sessionsEnabled())
			this.ipSessions.save(account.id(), session.address(), this.now().plus(this.login.sessionDuration()));

		session.authenticate(method, this.accounts.findById(account.id()).orElse(account));
		this.publish(session);
	}

	public CompletableFuture<Result> changePassword(PlayerSession session, String oldPassword, String newPassword) {
		return this.guarded(session, () -> {
			final Result state = this.requireAuthenticatedPassword(session);

			if (state != null)
				return done(state);

			final Result weak = this.weak(session, newPassword);

			if (weak != null)
				return done(weak);

			return this.verifyOwn(session, oldPassword).thenCompose(check -> {
				if (check.result() != null)
					return done(check.result());

				return this.hashPool.submit(() -> this.hasher.hash(newPassword)).thenCompose(hashed -> this.database.supplyAsync(() -> {
					if (!this.accounts.updatePassword(check.account().id(), check.account().version(), hashed.hash(), hashed.algorithm()))
						return Result.of(Outcome.CONFLICT);

					// Another address that was logged in must prove itself again with the new password.
					this.ipSessions.delete(check.account().id());
					this.accounts.findById(check.account().id()).ifPresent(session::updateAccount);
					this.bestEffort(() -> this.audit.log(Event.PASSWORD_CHANGED, session.uuid(), session.username(), session.address(), null, this.nodeId, null));

					return Result.of(Outcome.OK);
				}));
			});
		});
	}

	public CompletableFuture<Result> unregister(PlayerSession session, String password) {
		return this.guarded(session, () -> {
			final Result state = this.requireAuthenticatedPassword(session);

			if (state != null)
				return done(state);

			return this.verifyOwn(session, password).thenCompose(check -> {
				if (check.result() != null)
					return done(check.result());

				return this.database.supplyAsync(() -> {
					if (!this.accounts.delete(check.account().id()))
						return Result.of(Outcome.CONFLICT);

					this.bestEffort(() -> this.audit.log(Event.UNREGISTER, session.uuid(), session.username(), session.address(), null, this.nodeId, null));

					return Result.of(Outcome.OK_RECONNECT);
				});
			});
		});
	}

	private record OwnPassword(Result result, Account account) {
	}

	/** Checks the password of an already logged-in player for a sensitive command. A wrong one counts like a wrong password at login. */
	private CompletableFuture<OwnPassword> verifyOwn(PlayerSession session, String password) {
		return this.database.supplyAsync(() -> this.accounts.findById(session.account().id())).thenCompose(fresh -> {
			if (fresh.isEmpty())
				return done(new OwnPassword(Result.of(Outcome.CONFLICT), null));

			return this.verify(fresh.get(), password).thenCompose(correct -> {
				if (correct)
					return done(new OwnPassword(null, fresh.get()));

				return this.database.supplyAsync(() -> new OwnPassword(this.wrongPassword(session), null));
			});
		});
	}

	/** From a premium connection to a non-premium account: the player proves, through Mojang, that the name is an original one. Two steps. */
	public CompletableFuture<Result> premium(PlayerSession session) {
		return this.guarded(session, () -> {
			final Result state = this.requireAuthenticatedAccount(session);

			if (state != null)
				return done(state);

			if (session.account().premium())
				return done(Result.of(Outcome.PREMIUM_ACCOUNT));

			if (!session.confirm("premium", this.now(), CONFIRM_WINDOW))
				return done(Result.of(Outcome.CONFIRM_REQUIRED));

			return this.database.supplyAsync(() -> {
				this.premium.forget(session.username()); // ask the services again, not the cache

				final PremiumResolver.Result lookup = this.premium.resolve(session.username());

				return switch (lookup.status()) {
					case UNKNOWN -> Result.of(Outcome.LOOKUP_FAILED);
					case CRACKED -> Result.of(Outcome.NOT_ORIGINAL_NAME);
					case PREMIUM -> {
						final Account account = this.accounts.findById(session.account().id()).orElseThrow(() -> new SQLException("the account vanished"));

						if (!this.accounts.setPremium(account.id(), account.version(), lookup.uuid()))
							yield Result.of(Outcome.CONFLICT);

						this.ipSessions.delete(account.id());
						this.bestEffort(() -> this.audit.log(Event.PREMIUM_ENABLED, session.uuid(), session.username(), session.address(), null, this.nodeId, lookup.uuid().toString()));

						yield Result.of(Outcome.OK_RECONNECT);
					}
				};
			});
		});
	}

	/** From a premium account to a non-premium one with a password. Two steps; the player is already proven by Mojang. */
	public CompletableFuture<Result> cracked(PlayerSession session, String password, String confirmation) {
		return this.guarded(session, () -> {
			final Result state = this.requireAuthenticatedAccount(session);

			if (state != null)
				return done(state);

			if (!session.account().premium())
				return done(Result.of(Outcome.NOT_PREMIUM_ACCOUNT));

			if (!password.equals(confirmation))
				return done(Result.of(Outcome.PASSWORD_MISMATCH));

			final Result weak = this.weak(session, password);

			if (weak != null)
				return done(weak);

			if (!session.confirm("cracked", this.now(), CONFIRM_WINDOW))
				return done(Result.of(Outcome.CONFIRM_REQUIRED));

			return this.hashPool.submit(() -> this.hasher.hash(password)).thenCompose(hashed -> this.database.supplyAsync(() -> {
				final Account account = this.accounts.findById(session.account().id()).orElseThrow(() -> new SQLException("the account vanished"));

				if (!this.accounts.setCracked(account.id(), account.version(), hashed.hash(), hashed.algorithm()))
					return Result.of(Outcome.CONFLICT);

				this.bestEffort(() -> this.audit.log(Event.CRACKED_ENABLED, session.uuid(), session.username(), session.address(), null, this.nodeId, null));

				return Result.of(Outcome.OK_RECONNECT);
			}));
		});
	}

	// ------------------------------------------------------------------------------------------
	// Changes made behind the proxy's back
	// ------------------------------------------------------------------------------------------

	/** An admin logged the player in. */
	public void forceAuthenticated(PlayerSession session) {
		try {
			final Account account = session.account() == null ? null : this.accounts.findById(session.account().id()).orElse(null);

			session.authenticate(AuthMethod.FORCED, account);
			this.publish(session);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not apply a forced login to " + session.username() + ": " + ex.getMessage());
		}
	}

	/** An admin logged the player out. They must log in or register again. */
	public void forceLoggedOut(PlayerSession session) {
		try {
			final Account account = session.account() == null ? null : this.accounts.findById(session.account().id()).orElse(null);

			session.reset(account == null ? AuthState.PENDING_REGISTER : AuthState.PENDING_LOGIN, account);
			this.publish(session);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not apply a forced logout to " + session.username() + ": " + ex.getMessage());
		}
	}

	/** Reloads the account from the database. @return the account, or empty if it is gone */
	public Optional<Account> refresh(PlayerSession session) {
		try {
			if (session.account() == null)
				return Optional.empty();

			final Optional<Account> fresh = this.accounts.findById(session.account().id());

			fresh.ifPresent(session::updateAccount);

			return fresh;

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not reload the account of " + session.username() + ": " + ex.getMessage());

			return session.account() == null ? Optional.empty() : Optional.of(session.account());
		}
	}

	/** Writes the player's state to the database and tells the platform again, e.g. when a row someone deleted should be there. */
	public void republish(PlayerSession session) {
		this.publish(session);
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	private Result requireState(PlayerSession session, AuthState wanted) {
		final AuthState state = session.state();

		if (state == wanted)
			return null;

		return Result.of(state == AuthState.AUTHENTICATED ? Outcome.ALREADY_LOGGED_IN : wanted == AuthState.PENDING_LOGIN ? Outcome.NOT_REGISTERED : Outcome.ALREADY_REGISTERED);
	}

	private Result requireAuthenticatedAccount(PlayerSession session) {
		if (!session.authenticated())
			return Result.of(Outcome.NOT_LOGGED_IN);

		return session.account() == null ? Result.of(Outcome.NOT_REGISTERED) : null;
	}

	private Result requireAuthenticatedPassword(PlayerSession session) {
		final Result base = this.requireAuthenticatedAccount(session);

		if (base != null)
			return base;

		return session.account().premium() || !session.account().hasPassword() ? Result.of(Outcome.PREMIUM_ACCOUNT) : null;
	}

	private Result weak(PlayerSession session, String password) {
		final Optional<PasswordPolicy.Violation> violation = this.policy.check(session.username(), password);

		return violation.map(value -> new Result(Outcome.WEAK_PASSWORD, value == PasswordPolicy.Violation.TOO_SHORT ? value.name() + ":" + this.policy.minLength() : value.name())).orElse(null);
	}

	private static <T> CompletableFuture<T> done(T value) {
		return CompletableFuture.completedFuture(value);
	}

	/** Runs a command that changes the account if none of this player's is running, and always frees the slot afterwards. */
	private CompletableFuture<Result> guarded(PlayerSession session, Supplier<CompletableFuture<Result>> command) {
		if (!session.tryBegin())
			return done(Result.of(Outcome.BUSY));

		final CompletableFuture<Result> future;

		try {
			future = command.get();

		} catch (final RuntimeException ex) {
			session.end();

			return done(this.failure(ex));
		}

		return future.handle((result, ex) -> ex == null ? result : this.failure(ex)).whenComplete((result, ex) -> session.end());
	}

	private Result failure(Throwable ex) {
		final Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;

		if (cause instanceof HashPool.BusyException)
			return Result.of(Outcome.BUSY);

		this.logger.log(Level.WARNING, "A login command failed", cause);

		return Result.of(Outcome.ERROR);
	}

	@FunctionalInterface
	private interface SqlTask {
		void run() throws SQLException;
	}

	/** For writes that are good to have but must never fail the thing that was actually asked for (the audit trail, the presence row). */
	private void bestEffort(SqlTask task) {
		try {
			task.run();

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "A database write that is not essential failed: " + ex.getMessage());
		}
	}
}
