package pl.kingdomcraft.authenticator.common.shadow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Decision;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Mode;

/**
 * Shadow mode: Authenticator runs next to another login plugin (mineLogin) and only writes down whether it would have decided the same.
 * For every connection it is told what the other plugin left of the connection once it had run, and compares that with its own
 * decision. Nothing here touches a connection.
 *
 * <p>Disagreements are kept in a small ring buffer for {@code /authadmin status} and written to the log at a capped rate, so a
 * misconfiguration that disagrees on every connection cannot flood the log.
 */
public final class ShadowComparator {

	/** What the other plugin made of the connection. */
	public enum Observed {

		/** Mojang must confirm the connection. */
		ONLINE,

		/** The connection is not checked with Mojang. */
		OFFLINE,

		/** The connection was refused. */
		DENIED,

		/** Nobody decided, so Velocity's own online-mode setting applies. Not comparable without knowing that setting. */
		UNDECIDED
	}

	public enum Verdict {
		AGREE,
		DISAGREE,
		UNDECIDED
	}

	/** One difference worth looking at, both sides written as text. */
	public record Disagreement(Instant at, String username, String ours, String theirs) {

		@Override
		public String toString() {
			return this.username + ": we would " + this.ours + ", the other plugin: " + this.theirs;
		}
	}

	/** The totals since start. */
	public record Stats(long agree, long disagree, long undecided, long uuidAgree, long uuidDisagree) {

		public long compared() {
			return this.agree + this.disagree;
		}
	}

	private static final int RECENT = 50;
	private static final int LOG_BURST = 10;
	private static final long LOG_WINDOW_MILLIS = 60_000;

	private final Clock clock;
	private final Consumer<String> log;
	private final AtomicLong agree = new AtomicLong();
	private final AtomicLong disagree = new AtomicLong();
	private final AtomicLong undecided = new AtomicLong();
	private final AtomicLong uuidAgree = new AtomicLong();
	private final AtomicLong uuidDisagree = new AtomicLong();
	private final ArrayDeque<Disagreement> recent = new ArrayDeque<>();

	private long logWindowStart;
	private int logged;
	private long suppressed;

	/** @param log where disagreements are written, one line each, at most ten a minute */
	public ShadowComparator(Clock clock, Consumer<String> log) {
		this.clock = clock;
		this.log = log;
	}

	public Verdict compare(String username, Decision ours, Observed theirs) {
		if (theirs == Observed.UNDECIDED) {
			this.undecided.incrementAndGet();

			return Verdict.UNDECIDED;
		}

		if (matches(ours.mode(), theirs)) {
			this.agree.incrementAndGet();

			return Verdict.AGREE;
		}

		this.disagree.incrementAndGet();
		this.note(new Disagreement(Instant.ofEpochMilli(this.clock.millis()), username, describe(ours), theirs.name()));

		return Verdict.DISAGREE;
	}

	/** Compares the UUID this plugin would give the player with the one the profile has after the other plugin has run. */
	public Verdict compareUuid(String username, UUID ours, UUID theirs) {
		if (ours.equals(theirs)) {
			this.uuidAgree.incrementAndGet();

			return Verdict.AGREE;
		}

		this.uuidDisagree.incrementAndGet();
		this.note(new Disagreement(Instant.ofEpochMilli(this.clock.millis()), username, "use the UUID " + ours, "the UUID is " + theirs));

		return Verdict.DISAGREE;
	}

	private static boolean matches(Mode ours, Observed theirs) {
		return switch (ours) {
			case ONLINE -> theirs == Observed.ONLINE;
			case OFFLINE -> theirs == Observed.OFFLINE;
			case DENY -> theirs == Observed.DENIED;
		};
	}

	private static String describe(Decision decision) {
		return switch (decision.mode()) {
			case ONLINE -> "require Mojang (" + decision.reason() + ")";
			case OFFLINE -> "ask for a password (" + decision.reason() + ")";
			case DENY -> "refuse (" + decision.reason() + ")";
		};
	}

	private synchronized void note(Disagreement disagreement) {
		if (this.recent.size() == RECENT)
			this.recent.removeFirst();

		this.recent.addLast(disagreement);

		final long now = this.clock.millis();

		if (now - this.logWindowStart >= LOG_WINDOW_MILLIS) {
			if (this.suppressed > 0)
				this.log.accept("Shadow mode: " + this.suppressed + " more disagreements in the last minute are not listed here; see /authadmin status.");

			this.logWindowStart = now;
			this.logged = 0;
			this.suppressed = 0;
		}

		if (this.logged < LOG_BURST) {
			this.logged++;
			this.log.accept("Shadow mode disagreement: " + disagreement);

		} else {
			this.suppressed++;
		}
	}

	public Stats stats() {
		return new Stats(this.agree.get(), this.disagree.get(), this.undecided.get(), this.uuidAgree.get(), this.uuidDisagree.get());
	}

	/** The latest disagreements, oldest first. */
	public synchronized List<Disagreement> recent() {
		return List.copyOf(this.recent);
	}
}
