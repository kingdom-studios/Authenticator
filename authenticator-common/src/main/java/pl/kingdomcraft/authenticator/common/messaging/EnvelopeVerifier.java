package pl.kingdomcraft.authenticator.common.messaging;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Decides whether a received message may be acted on, and signs the ones this node sends. Every transport (PluginMessaging, Redis)
 * goes through it, so they all apply the same rules.
 *
 * <p>A message is accepted only if it is small enough, well formed, correctly signed, recent (within the clock skew) and not seen
 * before. Everything else is dropped and counted, never delivered. Thread-safe.
 */
public final class EnvelopeVerifier {

	/** Largest accepted or published message, in characters. Velocity's own limit for a plugin message to a server is 32 KiB. */
	public static final int MAX_MESSAGE_LENGTH = 16 * 1024;

	/** Rejection warnings are logged at most this often, so someone who can send messages cannot flood the log. */
	private static final long WARN_INTERVAL_MS = 30_000;

	private final HmacSigner signer;
	private final Duration maxSkew;
	private final Clock clock;
	private final Logger logger;

	/** Memory of recent message ids. It must outlive the clock-skew window, otherwise a replayed message could slip through. */
	private final Cache<String, Boolean> seen;

	private final AtomicLong rejectedSignatures = new AtomicLong();
	private final AtomicLong rejectedStale = new AtomicLong();
	private final AtomicLong rejectedMalformed = new AtomicLong();
	private volatile long lastWarnAt;

	public EnvelopeVerifier(HmacSigner signer, Duration maxSkew, Clock clock, Logger logger) {
		this.signer = signer;
		this.maxSkew = maxSkew;
		this.clock = clock;
		this.logger = logger;
		this.seen = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(maxSkew.multipliedBy(2).plusMinutes(5)).build();
	}

	public Duration maxSkew() {
		return this.maxSkew;
	}

	/**
	 * Signs the envelope and renders it as the text that goes on the wire.
	 *
	 * @throws BusException if the message is too large
	 */
	public String sign(Envelope envelope) {
		final String json = this.signer.sign(envelope).toJson();

		if (json.length() > MAX_MESSAGE_LENGTH)
			throw new BusException("Message of type " + envelope.type() + " is " + json.length() + " characters, over the " + MAX_MESSAGE_LENGTH + " limit");

		return json;
	}

	/**
	 * @param source where the message came from, for the log only (a channel name)
	 * @return the envelope if it passed every check, empty if it was dropped
	 */
	public Optional<Envelope> accept(String message, String source) {
		if (message.length() > MAX_MESSAGE_LENGTH) {
			this.rejectedMalformed.incrementAndGet();
			this.warnLimited("Dropped an oversized message from " + source + " (" + message.length() + " characters)");

			return Optional.empty();
		}

		final Envelope envelope;

		try {
			envelope = Envelope.fromJson(message);
		} catch (final Envelope.InvalidEnvelopeException ex) {
			this.rejectedMalformed.incrementAndGet();
			this.warnLimited("Dropped a malformed message from " + source + ": " + ex.getMessage());

			return Optional.empty();
		}

		if (!this.signer.verify(envelope)) {
			this.rejectedSignatures.incrementAndGet();
			this.warnLimited("Dropped a message (" + envelope.type() + ") from '" + envelope.node() + "' via " + source + ": invalid signature. Check that the secret matches on every node.");

			return Optional.empty();
		}

		if (!HmacSigner.isFresh(envelope, this.clock, this.maxSkew)) {
			this.rejectedStale.incrementAndGet();
			this.warnLimited("Dropped a message (" + envelope.type() + ") from '" + envelope.node() + "': its timestamp is more than " + this.maxSkew.toSeconds()
					+ "s from this node's clock. Check that the clocks of the proxy and the servers are synchronized.");

			return Optional.empty();
		}

		// Only authentic, fresh ids are remembered, so forged ids cannot suppress real messages.
		if (this.seen.asMap().putIfAbsent(envelope.id(), Boolean.TRUE) != null) {
			this.rejectedStale.incrementAndGet();

			return Optional.empty();
		}

		return Optional.of(envelope);
	}

	public long rejectedSignatures() {
		return this.rejectedSignatures.get();
	}

	/** Messages dropped for being too old, too far in the future, or already seen. */
	public long rejectedStale() {
		return this.rejectedStale.get();
	}

	/** Messages dropped for being oversized or not a valid envelope. */
	public long rejectedMalformed() {
		return this.rejectedMalformed.get();
	}

	/** Logs a warning unless one was logged recently. */
	public void warnLimited(String message) {
		final long now = this.clock.millis();

		if (now - this.lastWarnAt >= WARN_INTERVAL_MS) {
			this.lastWarnAt = now;
			this.logger.warning(message);
		}
	}
}
