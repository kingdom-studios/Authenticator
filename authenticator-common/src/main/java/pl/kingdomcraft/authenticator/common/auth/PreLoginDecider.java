package pl.kingdomcraft.authenticator.common.auth;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import pl.kingdomcraft.authenticator.common.limit.RateLimiter;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.IpBlockRepository;
import pl.kingdomcraft.authenticator.common.settings.LimitSettings;
import pl.kingdomcraft.authenticator.common.settings.LoginSettings;
import pl.kingdomcraft.authenticator.common.settings.PremiumSettings;

/**
 * Decides, before the player is let in, whether the connection is Mojang-verified (online), password-protected (offline) or refused.
 * The order is cheapest first, so a flood is stopped before it costs a database query or a request to a lookup service: connection
 * rate, name, blocked address, already online, then the account, and only for a name nobody has registered, Mojang.
 *
 * <p>Blocking: call it from an asynchronous event task. It never changes anything except in-memory counters.
 */
public final class PreLoginDecider {

	public enum Mode {
		ONLINE,
		OFFLINE,
		DENY
	}

	public enum Reason {
		// refused
		TOO_MANY_CONNECTIONS,
		BAD_NICKNAME,
		IP_BLOCKED,
		ALREADY_ONLINE,
		WRONG_CASE,
		PREMIUM_LOOKUP_FAILED,
		DATABASE_ERROR,
		// online
		PREMIUM_ACCOUNT,
		ORIGINAL_NAME,
		RENAMED_PREMIUM,
		// offline
		REGISTERED_ACCOUNT,
		NEW_NON_PREMIUM,
		CLAIM_FAILED,
		LAUNCHER_UUID
	}

	/**
	 * @param clientUuid the UUID the client announced, if it did (1.19+); a cracked launcher usually announces another one than the original account has
	 */
	public record Request(String username, InetAddress address, UUID clientUuid) {
	}

	/**
	 * @param detail extra for the message: the correct name for {@link Reason#WRONG_CASE}, the time a block ends for {@link Reason#IP_BLOCKED}
	 * @param source what the decision rested on, for the log (for example a lookup service)
	 */
	public record Decision(Mode mode, Reason reason, String detail, String source) {

		static Decision deny(Reason reason, String detail) {
			return new Decision(Mode.DENY, reason, detail, "");
		}
	}

	private final LoginSettings login;
	private final PremiumSettings premium;
	private final AccountRepository accounts;
	private final IpBlockRepository blocks;
	private final PremiumResolver resolver;
	private final SessionManager sessions;
	private final Clock clock;
	private final Logger logger;
	private final RateLimiter<InetAddress> connections;
	private final Cache<InetAddress, Optional<Instant>> blockCache;
	private final Cache<String, Boolean> claims;

	public PreLoginDecider(LoginSettings login, LimitSettings limits, PremiumSettings premium, AccountRepository accounts, IpBlockRepository blocks, PremiumResolver resolver, SessionManager sessions,
			Clock clock, Logger logger) {
		this.login = login;
		this.premium = premium;
		this.accounts = accounts;
		this.blocks = blocks;
		this.resolver = resolver;
		this.sessions = sessions;
		this.clock = clock;
		this.logger = logger;
		this.connections = new RateLimiter<>(limits.connectionsPerIp(), limits.connectionsWindow(), clock);

		final com.github.benmanes.caffeine.cache.Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());

		// A block is looked up at most once every few seconds per address, however fast it reconnects.
		this.blockCache = Caffeine.newBuilder().maximumSize(50_000).ticker(ticker).expireAfterWrite(Duration.ofSeconds(5)).build();
		this.claims = Caffeine.newBuilder().maximumSize(50_000).ticker(ticker).expireAfterWrite(premium.claim()).build();
	}

	public Decision decide(Request request) {
		if (!this.connections.tryAcquire(request.address()))
			return Decision.deny(Reason.TOO_MANY_CONNECTIONS, "");

		if (!this.login.nickname().matcher(request.username()).matches())
			return Decision.deny(Reason.BAD_NICKNAME, "");

		try {
			final Optional<Instant> blockedUntil = this.blockedUntil(request.address());

			if (blockedUntil.isPresent())
				return Decision.deny(Reason.IP_BLOCKED, blockedUntil.get().toString());

			if (this.sessions.isOnline(request.username()))
				return Decision.deny(Reason.ALREADY_ONLINE, "");

			final Optional<Account> account = this.accounts.findByUsername(request.username());

			if (account.isPresent())
				return this.forExistingAccount(request, account.get());

			return this.forNewName(request);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not decide on the connection of " + request.username() + ": " + ex.getMessage());

			return Decision.deny(Reason.DATABASE_ERROR, "");
		}
	}

	private Optional<Instant> blockedUntil(InetAddress address) throws SQLException {
		final Optional<Instant> cached = this.blockCache.getIfPresent(address);

		if (cached != null && cached.map(until -> until.isAfter(Instant.ofEpochMilli(this.clock.millis()))).orElse(true))
			return cached;

		final Optional<Instant> fresh = this.blocks.blockedUntil(address, Instant.ofEpochMilli(this.clock.millis()));

		this.blockCache.put(address, fresh);

		return fresh;
	}

	/** Tells the decider that an address was just blocked, so the next connection is refused without waiting for the cache. */
	public void noteBlocked(InetAddress address, Instant until) {
		this.blockCache.put(address, Optional.of(until));
	}

	private Decision forExistingAccount(Request request, Account account) {
		// The name is unique ignoring case, but showing up in another case would give the same account a second identity on the servers.
		if (!account.username().equals(request.username()))
			return Decision.deny(Reason.WRONG_CASE, account.username());

		return account.premium() ? new Decision(Mode.ONLINE, Reason.PREMIUM_ACCOUNT, "", "account") : new Decision(Mode.OFFLINE, Reason.REGISTERED_ACCOUNT, "", "account");
	}

	private Decision forNewName(Request request) throws SQLException {
		final PremiumResolver.Result result = this.resolver.resolve(request.username());

		return switch (result.status()) {
			case UNKNOWN -> Decision.deny(Reason.PREMIUM_LOOKUP_FAILED, "");
			case CRACKED -> new Decision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, "", result.source());
			case PREMIUM -> this.forOriginalName(request, result);
		};
	}

	private Decision forOriginalName(Request request, PremiumResolver.Result result) throws SQLException {
		// An original player who changed their name is still the owner of their account.
		final Optional<Account> renamed = this.accounts.findByPremiumUuid(result.uuid());

		if (renamed.isPresent())
			return new Decision(Mode.ONLINE, Reason.RENAMED_PREMIUM, renamed.get().username(), result.source());

		if (this.premium.policy() == PremiumPolicy.PROTECT)
			return new Decision(Mode.ONLINE, Reason.ORIGINAL_NAME, "", result.source());

		// ALLOW_CLAIM_AUTO: a client that announces some other UUID than the original account's is plainly not an original client.
		if (request.clientUuid() != null && !request.clientUuid().equals(result.uuid()))
			return new Decision(Mode.OFFLINE, Reason.LAUNCHER_UUID, "", result.source());

		// Otherwise try online once; a client that cannot pass Mojang's check comes back, and is let in as a non-premium player.
		final String claimKey = Account.key(request.username()) + "|" + request.address().getHostAddress();

		if (this.claims.getIfPresent(claimKey) != null)
			return new Decision(Mode.OFFLINE, Reason.CLAIM_FAILED, "", result.source());

		this.claims.put(claimKey, Boolean.TRUE);

		return new Decision(Mode.ONLINE, Reason.ORIGINAL_NAME, "", result.source());
	}

	/** The player passed Mojang's check, so the earlier online attempt did not fail after all. */
	public void claimSucceeded(String username, InetAddress address) {
		this.claims.invalidate(Account.key(username) + "|" + address.getHostAddress());
	}
}
