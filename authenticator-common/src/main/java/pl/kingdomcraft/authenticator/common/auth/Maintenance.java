package pl.kingdomcraft.authenticator.common.auth;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.repository.IpBlockRepository;
import pl.kingdomcraft.authenticator.common.repository.PremiumCacheRepository;
import pl.kingdomcraft.authenticator.common.repository.SessionRepository;

/** Removes what is no longer of any use: expired IP sessions and blocks, old answers of the lookup services, audit entries past their retention. Blocking; run it on a scheduler thread. */
public final class Maintenance {

	/** Answers older than this are useless even as the fallback when every lookup service is down. */
	static final Duration PREMIUM_CACHE_KEEP = Duration.ofDays(30);

	/** What one round removed. */
	public record Report(int sessions, int ipBlocks, int premiumCache, int audit) {

		public int total() {
			return this.sessions + this.ipBlocks + this.premiumCache + this.audit;
		}
	}

	private final SessionRepository sessions;
	private final IpBlockRepository blocks;
	private final PremiumCacheRepository premiumCache;
	private final AuditRepository audit;
	private final int auditRetentionDays;
	private final Clock clock;
	private final Logger logger;

	/** @param auditRetentionDays how many days audit entries are kept; 0 keeps them for ever */
	public Maintenance(SessionRepository sessions, IpBlockRepository blocks, PremiumCacheRepository premiumCache, AuditRepository audit, int auditRetentionDays, Clock clock, Logger logger) {
		this.sessions = sessions;
		this.blocks = blocks;
		this.premiumCache = premiumCache;
		this.audit = audit;
		this.auditRetentionDays = auditRetentionDays;
		this.clock = clock;
		this.logger = logger;
	}

	/** Never throws; a part that fails is logged and the others still run. */
	public Report runOnce() {
		final Instant now = Instant.ofEpochMilli(this.clock.millis());

		final int sessionsRemoved = this.attempt("expired sessions", () -> this.sessions.deleteExpired(now));
		final int blocksRemoved = this.attempt("expired IP blocks", () -> this.blocks.deleteExpired(now));
		final int cacheRemoved = this.attempt("old lookup answers", () -> this.premiumCache.deleteOlderThan(now.minus(PREMIUM_CACHE_KEEP)));
		final int auditRemoved = this.auditRetentionDays == 0 ? 0 : this.attempt("old audit entries", () -> this.audit.deleteOlderThan(now.minus(Duration.ofDays(this.auditRetentionDays))));

		return new Report(sessionsRemoved, blocksRemoved, cacheRemoved, auditRemoved);
	}

	private interface Cleanup {
		int run() throws SQLException;
	}

	private int attempt(String what, Cleanup cleanup) {
		try {
			return cleanup.run();

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not remove " + what + ": " + ex.getMessage());

			return 0;
		}
	}
}
