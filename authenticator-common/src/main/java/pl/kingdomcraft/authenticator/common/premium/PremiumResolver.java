package pl.kingdomcraft.authenticator.common.premium;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Expiry;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.repository.PremiumCacheRepository;

/**
 * Says whether a name belongs to an original account. It asks, in turn: a memory cache, the database cache, then the lookup services in
 * the configured order; the first service that gives a definite answer (found, or not found) wins, one that is down or slow is skipped.
 * When none answers, an old database entry is better than nothing; a name never seen before is {@link Status#UNKNOWN} and the caller must
 * not guess.
 *
 * <p>Blocking: call it from an asynchronous event task, never from a thread that must not wait. Concurrent lookups of one name share a
 * single request, so a flood of connections with the same name does not become a flood of requests to the services.
 */
public final class PremiumResolver {

	public enum Status {
		PREMIUM,
		CRACKED,
		UNKNOWN
	}

	/** @param uuid the Mojang UUID, only for {@link Status#PREMIUM}
	 *  @param source where the answer came from: a service name, {@code memory}, {@code database} or {@code database-stale} */
	public record Result(Status status, UUID uuid, String source) {
	}

	/**
	 * @param memory   how long an answer stays in memory
	 * @param unknown  how long "no service answered" is remembered, short so the services are tried again soon without being hammered meanwhile
	 * @param positive how long a "this is an original account" answer in the database is trusted
	 * @param negative how long a "this is not an original account" answer in the database is trusted
	 */
	public record Timing(Duration memory, Duration unknown, Duration positive, Duration negative) {

		public static Timing defaults() {
			return new Timing(Duration.ofMinutes(10), Duration.ofSeconds(15), Duration.ofHours(24), Duration.ofHours(1));
		}
	}

	private final List<PremiumApi> apis;
	private final PremiumCacheRepository database;
	private final Timing timing;
	private final Clock clock;
	private final Logger logger;
	private final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();
	private final Cache<String, Result> memory;

	/** @param database the database cache; null for none (a read or write error there is only logged, never fatal) */
	public PremiumResolver(List<PremiumApi> apis, PremiumCacheRepository database, Timing timing, Clock clock, Logger logger) {
		this.apis = List.copyOf(apis);
		this.database = database;
		this.timing = timing;
		this.clock = clock;
		this.logger = logger;
		this.memory = Caffeine.newBuilder().maximumSize(50_000).ticker(() -> java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(clock.millis())).expireAfter(new Expiry<String, Result>() {
			@Override
			public long expireAfterCreate(String key, Result value, long currentTime) {
				return (value.status() == Status.UNKNOWN ? timing.unknown() : timing.memory()).toNanos();
			}

			@Override
			public long expireAfterUpdate(String key, Result value, long currentTime, long currentDuration) {
				return this.expireAfterCreate(key, value, currentTime);
			}

			@Override
			public long expireAfterRead(String key, Result value, long currentTime, long currentDuration) {
				return currentDuration;
			}
		}).build();
	}

	public Result resolve(String username) {
		final String key = Account.key(username);
		final Result cached = this.memory.getIfPresent(key);

		if (cached != null)
			return new Result(cached.status(), cached.uuid(), "memory");

		final CompletableFuture<Result> mine = new CompletableFuture<>();
		final CompletableFuture<Result> running = this.inFlight.putIfAbsent(key, mine);

		if (running != null)
			return running.join(); // someone else is already asking about this name

		try {
			final Result result = this.lookup(username, key);

			this.memory.put(key, result);
			mine.complete(result);

			return result;

		} catch (final RuntimeException ex) {
			mine.completeExceptionally(ex);

			throw ex;

		} finally {
			this.inFlight.remove(key);
		}
	}

	private Result lookup(String username, String key) {
		Optional<PremiumCacheRepository.Entry> stored = Optional.empty();

		if (this.database != null)
			try {
				stored = this.database.find(username);

			} catch (final SQLException ex) {
				this.logger.log(Level.WARNING, "Could not read the premium cache from the database: " + ex.getMessage());
			}

		if (stored.isPresent() && this.isFresh(stored.get()))
			return this.fromEntry(stored.get(), "database");

		final StringBuilder failures = new StringBuilder();

		for (final PremiumApi api : this.apis) {
			final PremiumApi.Answer answer = api.lookup(username);

			if (answer.kind() == PremiumApi.Kind.UNAVAILABLE) {
				failures.append(failures.length() == 0 ? "" : "; ").append(api.name()).append(": ").append(answer.detail());
				continue;
			}

			final Result result = answer.kind() == PremiumApi.Kind.FOUND ? new Result(Status.PREMIUM, answer.uuid(), api.name()) : new Result(Status.CRACKED, null, api.name());

			this.store(username, result);

			return result;
		}

		if (stored.isPresent()) {
			this.logger.warning("No premium lookup service answered for " + username + " (" + failures + "); using the older answer from the database");

			return this.fromEntry(stored.get(), "database-stale");
		}

		this.logger.warning("No premium lookup service answered for " + username + " (" + failures + ")");

		return new Result(Status.UNKNOWN, null, "none");
	}

	private boolean isFresh(PremiumCacheRepository.Entry entry) {
		final Duration limit = entry.premiumUuid() != null ? this.timing.positive() : this.timing.negative();

		return Duration.between(entry.checkedAt(), Instant.ofEpochMilli(this.clock.millis())).compareTo(limit) < 0;
	}

	private Result fromEntry(PremiumCacheRepository.Entry entry, String source) {
		return entry.premiumUuid() != null ? new Result(Status.PREMIUM, entry.premiumUuid(), source) : new Result(Status.CRACKED, null, source);
	}

	private void store(String username, Result result) {
		if (this.database == null)
			return;

		try {
			this.database.save(username, result.uuid(), result.source(), Instant.ofEpochMilli(this.clock.millis()));

		} catch (final SQLException ex) {
			this.logger.log(Level.WARNING, "Could not write the premium cache to the database: " + ex.getMessage());
		}
	}

	/** Forgets what is remembered about a name, e.g. after the player ran {@code /premium}. */
	public void forget(String username) {
		this.memory.invalidate(Account.key(username));
	}
}
